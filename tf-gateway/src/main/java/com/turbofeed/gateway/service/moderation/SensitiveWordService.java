package com.turbofeed.gateway.service.moderation;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   <li><b>即时刷新</b>：admin 增删后触发<b>后台异步</b>重建（{@link #triggerReload()}），
 *       不阻塞 HTTP 线程——DB 写入立即返回，AC 重建在单飞后台线程完成并原子切换；
 *       30s 定时刷新仍作最终兜底。</li>
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

    /**
     * 后台重建线程池（单线程，保证重建串行、避免并发全量重建相互踩踏）。
     *
     * <p>admin 变更只把 DB 写入落地，真正的 AC 重建交给它，HTTP 线程零等待——
     * 这是「大词库下动态添加也不卡管理面」的关键。守护线程，随 Spring 上下文关闭。</p>
     */
    private final ExecutorService reloadExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sensitive-word-reloader");
                t.setDaemon(true);
                return t;
            });

    /**
     * 单飞标志：重建进行中时，后续触发只置脏、不再重复提交任务。
     * 进行中的那次重建读的是最新 DB，重建后再比对指纹，期间的新变更会被追平（见 {@link #triggerReload}）。
     */
    private final AtomicBoolean reloadInFlight = new AtomicBoolean(false);

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
     * 在<b>已归一化</b>的文本上扫描，返回<b>全部</b>命中（词 + 分类 + 位置），按扫描顺序。
     *
     * <p>逐条把 {@link AhoCorasick.Match} 映射为分类齐全的 {@link SensitiveWordHit}。
     * 决策层（{@link ContentSecurityService#check}）用全部命中取最严重动作，
     * 避免「首词定生死」漏拦。无命中返回空列表（不返回 {@code null}）。</p>
     *
     * @param normalizedText 归一化后的文本
     * @return 命中列表（按扫描顺序）；无命中返回空列表
     */
    public List<SensitiveWordHit> matchAll(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) {
            return List.of();
        }
        Dictionary d = dictRef.get();
        List<AhoCorasick.Match> matches = d.ac().allMatches(normalizedText);
        if (matches.isEmpty()) {
            return List.of();
        }
        List<SensitiveWordHit> hits = new ArrayList<>(matches.size());
        for (AhoCorasick.Match m : matches) {
            hits.add(new SensitiveWordHit(m.word(), d.categoryOf(m.word()), m.start(), m.end()));
        }
        return hits;
    }

    /**
     * 在<b>已归一化</b>的文本上扫描，返回首个命中（词 + 分类 + 位置）。
     *
     * <p>委托 {@link #matchAll} 取首条，保持旧调用方行为不变；
     * 需要<b>全部</b>命中的决策应使用 {@link #matchAll}。位置是该归一化文本的下标，
     * 调用方需用 {@link TextNormalizer.Normalized#toOriginalIndex(int)} 回溯原文。</p>
     *
     * @param normalizedText 归一化后的文本
     * @return 命中详情；无命中返回 {@code null}
     */
    public SensitiveWordHit match(String normalizedText) {
        List<SensitiveWordHit> all = matchAll(normalizedText);
        return all.isEmpty() ? null : all.get(0);
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
        triggerReload(); // 后台异步重建，不阻塞 admin 线程
        return sw;
    }

    public void disable(long id) {
        repository.disable(id);
        triggerReload();
    }

    public void enable(long id) {
        repository.enable(id);
        triggerReload();
    }

    public void delete(long id) {
        repository.delete(id);
        triggerReload();
    }

    /**
     * 批量导入（一次加很多词的正确姿势）：一个 DB 批量 upsert + 一次后台重建。
     *
     * <p>逐条 {@link #add} 会在 HTTP 线程外触发多次重建；批量接口把 N 次写入合并成
     * 一条批处理 SQL，且只触发<b>一次</b>后台重建，是运营「整批导入词库」的低开销入口。</p>
     *
     * @param words    待导入词条（去空白、非空）
     * @param category 这批词统一归属的分类
     * @return 实际写入条数（去重后）
     */
    public int batchAdd(List<String> words, String category) {
        if (words == null || words.isEmpty()) {
            return 0;
        }
        String cat = (category == null || category.isBlank()) ? "DEFAULT" : category.trim();
        int n = repository.batchUpsert(words, cat);
        triggerReload();
        return n;
    }

    /**
     * 后台异步重建（单飞）：admin 变更走这里，HTTP 线程立即返回。
     *
     * <p><b>单飞语义</b>：若重建正在进行，本次触发只置脏、不重复提交任务；进行中的那次
     * 重建读的是最新 DB，完成后比对指纹，若期间又有新变更则<b>再追一轮</b>（最多 3 轮），
     * 保证「最后写的那条词」一定进入下一个生效快照——既不被漏、也不重复全量重建。</p>
     */
    public void triggerReload() {
        if (!reloadInFlight.compareAndSet(false, true)) {
            return; // 已在飞行中，本轮变更会被进行中的重建追平
        }
        reloadExecutor.submit(() -> {
            try {
                int guard = 0;
                do {
                    doReload(null);
                    guard++;
                } while (guard < 3 && !repository.checkSum().equals(lastCheckSum));
            } catch (Exception e) {
                log.warn("敏感词库后台刷新失败（保留旧 Trie）: {}", e.getMessage());
            } finally {
                reloadInFlight.set(false);
            }
        });
    }

    /** Spring 上下文销毁时关闭后台重建线程池，避免线程泄漏。 */
    @PreDestroy
    public void shutdown() {
        reloadExecutor.shutdown();
    }

    /**
     * 后台管理列表（支持关键词搜索 / 分类过滤）。
     *
     * @param keyword  包含匹配词（空=不过滤）
     * @param category 分类精确过滤（空或 ALL=全部）
     * @return {@code {items, dbCount(过滤后总数), acLoadedSize(当前 AC 加载数)}}
     */
    public Map<String, Object> list(int limit, long offset, String keyword, String category) {
        List<SensitiveWord> rows = repository.findByKeyword(keyword, category, limit, offset);
        long filtered = repository.countByKeyword(keyword, category);
        return Map.of("items", rows, "dbCount", filtered, "acLoadedSize", loadedSize);
    }
}
