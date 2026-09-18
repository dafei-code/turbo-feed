package com.turbofeed.gateway.service.moderation;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 敏感词服务（动态、不停机）—— ③匹配层。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li><b>热更新</b>：词库变更无需重启、不阻塞请求；后台构建新快照，CAS 整体切换。</li>
 *   <li><b>零锁读</b>：快照不可变，读路径直接 {@code AtomicReference.get()}，
 *       无锁、无阻塞、无中断风险。</li>
 *   <li><b>变更检测</b>：30s 定时 {@code (COUNT, MAX(updated_at))} 指纹对比，
 *       比对变化才重建（INSERT/UPDATE/DELETE 任一改变指纹）。</li>
 *   <li><b>即时刷新</b>：admin 增删后同步调用 {@link #reload()}，不等 30s 窗口。</li>
 *   <li><b>失败安全</b>：刷新异常保留旧快照（不抛、不清空）。</li>
 * </ul>
 *
 * <h3>本层的职责边界（重要）</h3>
 * <p><b>本类只做匹配，不做决策</b>：喂进来的文本必须<b>已经归一化</b>，
 * 返回结果是带分类与位置的{@link SensitiveWordHit}，<b>不抛业务异常</b>。
 * 「命中之后是拒、是转人审、还是放行降权」由白名单与策略决定，属
 * {@link ContentSecurityService}（①统一入口 + ④决策层）。</p>
 *
 * <p>为什么要这样切：改造前 {@code requireClean(text)} 同时承担了「匹配」与
 * 「抛异常拒绝」两件事，于是每个调用方都直接绑死在「命中即拒」这一种策略上，
 * 想给某个场景换策略（比如昵称拒绝、评论转人审）就得改所有调用点。
 * 分开之后，策略调整只动决策层一处。</p>
 *
 * <p><b>写路径请勿直接调用本类</b>——直接调用会绕过归一化（抗绕过失效）
 * 与白名单（误杀无出口）。统一走 {@link ContentSecurityService#requireClean}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    private final SensitiveWordRepository repository;

    /**
     * 词库快照：AC 自动机 + 「词 → 分类」映射。
     *
     * <p>两者必须<b>由同一个原子引用一起切换</b>：分类是决策层选动作的依据，
     * 若 AC 已换成新的而分类表还是旧的，就会出现「新词命中了，但按旧分类处置」
     * 的不一致窗口；拆成两个 AtomicReference 时这个窗口必然存在。</p>
     */
    private record Dictionary(AhoCorasick ac, Map<String, String> categoryByWord) {

        static final Dictionary EMPTY = new Dictionary(AhoCorasick.EMPTY, Map.of());

        /** 取词条分类；未登记返回 {@code null}（决策层回落默认动作）。 */
        String categoryOf(String word) {
            return categoryByWord.get(word);
        }
    }

    /** 当前生效的词库快照（不可变，CAS 整体切换）。 */
    private final AtomicReference<Dictionary> dictRef = new AtomicReference<>(Dictionary.EMPTY);

    /** 上次重建时的变更指纹，用于定时检测是否有变化。 */
    private volatile SensitiveWordRepository.CheckSum lastCheckSum =
            new SensitiveWordRepository.CheckSum(0L, Instant.EPOCH);

    /** 上次成功加载的词条数（供 health / admin 展示）。 */
    private volatile int loadedSize = 0;

    /** 启动时同步加载一次（避免前 30s 词库为空）。 */
    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.error("敏感词库启动加载失败（保留空 Trie，过滤当前不生效）: {}", e.getMessage(), e);
        }
    }

    /**
     * 30s 定时检测：指纹变化才重建。
     *
     * <p>固定 30s 是「动态」的常规阈值（变更秒级可感知）；
     * admin 增删会立即触发 {@link #reload()}，不等定时器。
     * 异常仅记日志、保留旧快照——旧词库总比空词库好。</p>
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

    /** 全量重建（admin 增删后立即调用，或外部强制刷新）。返回加载的词条数。 */
    public int reload() {
        return doReload(null);
    }

    private int doReload(SensitiveWordRepository.CheckSum precomputed) {
        List<SensitiveWord> words = repository.findAllEnabled();
        Map<String, String> categoryByWord = new HashMap<>(Math.max(16, words.size() * 2));
        List<String> wordList = new ArrayList<>(words.size());
        for (SensitiveWord w : words) {
            if (w.word() == null || w.word().trim().isEmpty()) {
                continue;
            }
            // key 用 trim 后的形态：AhoCorasick 内部也 trim，两边必须一致，
            // 否则命中返回的词在分类表里查不到，动作会静默回落到默认值
            String key = w.word().trim();
            wordList.add(key);
            categoryByWord.put(key, w.category());
        }
        AhoCorasick ac = new AhoCorasick(wordList);
        dictRef.set(new Dictionary(ac, Map.copyOf(categoryByWord)));
        SensitiveWordRepository.CheckSum cs = precomputed != null ? precomputed : repository.checkSum();
        lastCheckSum = cs;
        loadedSize = ac.size();
        log.info("敏感词 AC 重建完成: trieSize={}, dbCount={}", ac.size(), cs.count());
        return ac.size();
    }

    /**
     * 在<b>已归一化</b>的文本上扫描，返回首个命中（词 + 分类 + 位置）。
     *
     * <p>位置是该归一化文本的下标，调用方需用
     * {@link TextNormalizer.Normalized#toOriginalIndex(int)} 回溯原文。</p>
     *
     * @param normalizedText 归一化后的文本
     * @return 命中详情；无命中返回 {@code null}
     */
    public SensitiveWordHit match(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) {
            return null;
        }
        Dictionary d = dictRef.get();
        AhoCorasick.Match m = d.ac().firstMatchDetail(normalizedText);
        if (m == null) {
            return null;
        }
        return new SensitiveWordHit(m.word(), d.categoryOf(m.word()), m.start(), m.end());
    }

    /** 当前词库大小（已加载到 AC 的词条数）。 */
    public int currentSize() {
        return loadedSize;
    }

    /**
     * 词库表里的行数（含禁用）。
     *
     * <p>与 {@link #currentSize()} 的差值能暴露加载异常：DB 有 100 行、
     * 只加载了 3 条 → 说明 enabled 字段或 SQL 有问题。单看任何一个数都看不出来。</p>
     */
    public long count() {
        return repository.count();
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
}
