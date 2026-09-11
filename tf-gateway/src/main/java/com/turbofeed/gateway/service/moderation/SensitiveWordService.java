package com.turbofeed.gateway.service.moderation;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 敏感词服务（动态、不停机）。
 *
 * <h3>设计目标（对标抖音/字节内容安全）</h3>
 * <ul>
 *   <li><b>热更新</b>：词库变更无需重启、不阻塞请求；后台构建新 AC，CAS 切换引用。</li>
 *   <li><b>零锁读</b>：AC 不可变，读路径直接 {@code AtomicReference.get()}，
 *       无锁、无阻塞、无中断风险。</li>
 *   <li><b>变更检测</b>：30s 定时 {@code (COUNT, MAX(updated_at))} 指纹对比，
 *       比对变化才重建（INSERT/UPDATE/DELETE 任一改变指纹）。
 *       生产大词库可演进为增量合并（按 revision 范围拉取）。</li>
 *   <li><b>即时刷新</b>：admin 增删后同步调用 {@link #reload()}，不等 30s 窗口。</li>
 *   <li><b>失败安全</b>：定时刷新异常保留旧 AC（不抛、不清空）；命中敏感词
 *       fail-closed 抛 {@link BizException}({@link ErrorCode#SENSITIVE_WORD_HIT})。</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 *   // 写路径：提交评论/描述前
 *   sensitiveWordService.requireClean(content);
 *   // 管理后台：增删
 *   sensitiveWordService.add(word, category);
 *   sensitiveWordService.disable(id);
 *   sensitiveWordService.reload();
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    private final SensitiveWordRepository repository;

    /** 当前生效的 AC 自动机（不可变，CAS 切换）。 */
    private final AtomicReference<AhoCorasick> trieRef = new AtomicReference<>(AhoCorasick.EMPTY);

    /** 上次重建时的变更指纹，用于定时检测是否有变化。 */
    private volatile SensitiveWordRepository.CheckSum lastCheckSum =
            new SensitiveWordRepository.CheckSum(0L, java.time.Instant.EPOCH);

    /** 启动时同步加载一次（避免前 30s 词库为空）。 */
    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.error("敏感词库启动加载失败（保留空 Trie）: {}", e.getMessage(), e);
        }
    }

    /**
     * 30s 定时检测：指纹变化才重建 AC。
     *
     * <p>固定 30s 是「动态」的常规阈值（变更秒级可感知，生产可调到 5~10s）；
     * admin 增删会立即触发 {@link #reload()}，不等定时器。
     * 异常仅记日志、保留旧 AC，词库短暂不可用不至于让全站内容审核裸奔。</p>
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledReload() {
        try {
            SensitiveWordRepository.CheckSum current = repository.checkSum();
            if (current.equals(lastCheckSum)) {
                return;
            }
            int size = doReload(current);
            log.info("敏感词库定时刷新: count={}, trieSize={}", current.count(), size);
        } catch (Exception e) {
            log.warn("敏感词库定时刷新失败（保留旧 Trie）: {}", e.getMessage());
        }
    }

    /** 全量重建 AC（admin 增删后立即调用，或外部强制刷新）。返回加载的词条数。 */
    public int reload() {
        return doReload(null);
    }

    private int doReload(SensitiveWordRepository.CheckSum precomputed) {
        List<SensitiveWord> words = repository.findAllEnabled();
        List<String> wordList = words.stream().map(SensitiveWord::word).toList();
        AhoCorasick ac = new AhoCorasick(wordList);
        trieRef.set(ac);
        SensitiveWordRepository.CheckSum cs = precomputed != null ? precomputed : repository.checkSum();
        lastCheckSum = cs;
        log.info("敏感词 AC 重建完成: trieSize={}, dbCount={}", ac.size(), cs.count());
        return ac.size();
    }

    /**
     * 扫描文本：命中敏感词即抛 {@link BizException}({@link ErrorCode#SENSITIVE_WORD_HIT})。
     *
     * <p>写路径（评论/描述/文件名等）统一 fail-closed——避免「未命中 = 通过」
     * 导致词库空窗期内容裸奔。</p>
     */
    public void requireClean(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        AhoCorasick ac = trieRef.get();
        String hit = ac.firstMatch(text);
        if (hit != null) {
            log.warn("敏感词命中 fail-closed: hit={}, len={}", hit, text.length());
            throw new BizException(ErrorCode.SENSITIVE_WORD_HIT, "内容包含敏感词");
        }
    }

    /** 仅检查不抛（用于审计/打标场景）。 */
    public String firstHit(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return trieRef.get().firstMatch(text);
    }

    /** 当前词库大小（已加载到 AC 的词条数）。 */
    public int currentSize() {
        return trieRef.get().size();
    }

    // ========== 后台管理 CRUD（调用方负责权限校验） ==========

    public SensitiveWord add(String word, String category) {
        if (word == null || word.trim().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "敏感词不能为空");
        }
        String cat = (category == null || category.isBlank()) ? "DEFAULT" : category.trim();
        SensitiveWord sw = repository.upsert(word.trim(), cat);
        reload();
        return sw;
    }

    public void disable(long id) {
        repository.disable(id);
        reload();
    }

    public void enable(long id) {
        repository.enable(id);
        reload();
    }

    public void delete(long id) {
        repository.delete(id);
        reload();
    }

    public List<SensitiveWord> list(int limit, long offset) {
        return repository.findAll(limit, offset);
    }

    public long count() {
        return repository.count();
    }
}
