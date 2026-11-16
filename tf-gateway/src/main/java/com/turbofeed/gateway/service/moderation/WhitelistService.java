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
 * 白名单服务（误杀治理出口）—— 与 {@link SensitiveWordService} <b>同构</b>的热更新模型。
 *
 * <h3>为什么与词库用同一套机制而不是各写一套</h3>
 * 两者形状完全一致：<b>数据量小、写少读多、读在写路径上、变更必须秒级生效</b>。
 * 因此同样采用「不可变快照 + {@link AtomicReference} 整体切换 + 零锁读」，
 * 同样用 {@code (COUNT, MAX(updated_at))} 指纹做 30s 变更检测。
 * <b>同一套心智模型，运营不需要记两套规则。</b>
 *
 * <p><b>阶段 B2 起与词库完全对齐异步单飞模型</b>：改造前 admin 写路径与定时路径都是
 * 同步 {@link #reload()}，两条路径并发时后写的那次可能把「读到较早 DB 快照」的索引盖回去
 * （与词库在 changelog 0080 修过的窗口一模一样）；跨实例广播到达时若直接在 Redis 订阅线程里
 * 全量重建，还会堵住订阅线程。现在所有自动重建统一走 {@link #triggerReload()}：
 * 单线程后台执行、单飞 CAS 合并重复触发、完成后比对指纹最多追平 3 轮。
 * {@code /reload} 同步入口保留给运维强制刷新（低频、单线程手动调用）。</p>
 *
 * <h3>与词库的关键差异</h3>
 * <ul>
 *   <li><b>空表是正常状态</b>：词库为空 = 过滤整体失效（危险，需 WARN/告警）；
 *       白名单为空 = 没有任何豁免（正常，绝大多数部署初始就是空的）。
 *       所以本类<b>不做</b>空表启动守卫——照搬词库的守卫会把「还没配过豁免」
 *       误判成故障。</li>
 *   <li><b>失败方向相反</b>：词库加载失败要保留旧快照（宁可旧词库也不要裸奔）；
 *       白名单加载失败同样保留旧快照，但含义是<b>继续沿用旧的豁免集合</b>——
 *       若此时降级为空集合，会让原本被豁免的正常内容突然被拦，属误杀事故。
 *       两者都是「保留旧快照」，但理由必须写清楚，否则后来者会「顺手改成清空」。</li>
 * </ul>
 *
 * <h3>索引结构</h3>
 * {@code word → List<WhitelistEntry>}。按词建索引而非平铺扫描：一次检测最多命中一个词，
 * 只需在该词的条目里比场景与主体，代价 O(该词的条目数)（实际 1~3 条）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhitelistService {

    private final WhitelistRepository repository;

    /** 当前生效的索引（不可变，CAS 整体切换）。空 Map 表示无任何豁免。 */
    private final AtomicReference<Map<String, List<WhitelistEntry>>> indexRef =
            new AtomicReference<>(Map.of());

    /** 上次重建时的变更指纹。 */
    private volatile WhitelistRepository.CheckSum lastCheckSum =
            new WhitelistRepository.CheckSum(0L, Instant.EPOCH);

    /** 上次成功加载的条目数（供 health / admin 展示，0 表示当前无豁免）。 */
    private volatile int loadedSize = 0;

    /** 上次加载成功的时刻（排障用：判断快照是否陈旧）。 */
    private volatile Instant lastLoadedAt = Instant.EPOCH;

    /** 后台重建线程池：与词库同样单线程串行，避免并发全量重建相互踩踏。 */
    private final ExecutorService reloadExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "whitelist-reloader");
                t.setDaemon(true);
                return t;
            });

    /** 单飞标志：重建进行中时后续触发只置脏，由进行中的那次重建追平。 */
    private final AtomicBoolean reloadInFlight = new AtomicBoolean(false);

    /** 启动时同步加载一次；失败仅告警（空集合 = 无豁免 = 回到改造前行为，安全）。 */
    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.error("白名单启动加载失败（本次按「无豁免」运行，不影响主流程）: {}", e.getMessage(), e);
        }
    }

    /**
     * 30s 定时检测：指纹变化才触发后台重建。
     *
     * <p>必须走 {@link #triggerReload()} 而不是直接 {@code doReload(current)}：
     * 定时路径与 admin / 跨实例广播触发的后台重建并发时，两次 {@code indexRef.set(...)}
     * 存在「读到较早 DB 快照的那次后写、把新豁免集合回退」的窗口。共用单飞入口后
     * 所有自动重建串行发生，且重建完成再取最新指纹，不会用旧指纹覆盖。</p>
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledReload() {
        try {
            WhitelistRepository.CheckSum current = repository.checkSum();
            if (current.equals(lastCheckSum)) {
                return;
            }
            log.info("白名单定时检测到变更，触发后台重建: count={}", current.count());
            triggerReload();
        } catch (Exception e) {
            log.warn("白名单定时刷新失败（保留旧索引）: {}", e.getMessage());
        }
    }

    /** 全量重建索引（运维强制刷新入口，同步执行）。返回加载的条目数。 */
    public int reload() {
        return doReload(null);
    }

    private int doReload(WhitelistRepository.CheckSum precomputed) {
        List<WhitelistEntry> rows = repository.findAllEnabled();
        Map<String, List<WhitelistEntry>> index = new HashMap<>(Math.max(16, rows.size() * 2));
        for (WhitelistEntry e : rows) {
            if (e.word() == null || e.word().isEmpty()) {
                continue;
            }
            index.computeIfAbsent(e.word(), k -> new ArrayList<>(2)).add(e);
        }
        // 全局豁免优先：同一词既有全局条目又有用户条目时，命中前者即可短路，省一次比较
        index.replaceAll((k, v) -> {
            v.sort((a, b) -> Boolean.compare(
                    WhitelistEntry.SCOPE_GLOBAL.equals(b.scope()),
                    WhitelistEntry.SCOPE_GLOBAL.equals(a.scope())));
            return List.copyOf(v);
        });
        indexRef.set(Map.copyOf(index));
        lastCheckSum = precomputed != null ? precomputed : repository.checkSum();
        loadedSize = rows.size();
        lastLoadedAt = Instant.now();
        log.info("白名单索引重建完成: entries={}, words={}", rows.size(), index.size());
        return rows.size();
    }

    /**
     * 判断一次命中是否被豁免。
     *
     * @param word   命中的词（归一化后的形态）
     * @param scene  检测场景
     * @param userId 提交者（{@code 0} 表示无用户上下文，如系统内部调用）
     * @return 命中被豁免返回 {@code true}
     */
    public boolean isExempt(String word, ContentScene scene, long userId) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        List<WhitelistEntry> entries = indexRef.get().get(word);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        for (WhitelistEntry e : entries) {
            if (e.covers(scene, userId)) {
                return true;
            }
        }
        return false;
    }

    /** 当前内存索引里的条目数。 */
    public int currentSize() {
        return loadedSize;
    }

    /** 上次加载成功的时刻。 */
    public Instant lastLoadedAt() {
        return lastLoadedAt;
    }

    // ========== 后台管理 CRUD（调用方负责权限校验） ==========

    /**
     * 新增 / 更新豁免条目。
     *
     * @param word    被豁免的词（必填）
     * @param scene   生效场景；{@code null} / 空 → {@link WhitelistEntry#SCENE_ANY}（全部场景）
     * @param scope   生效范围；{@code null} / 空 → {@link WhitelistEntry#SCOPE_GLOBAL}
     * @param ownerId {@code scope=USER} 时的用户 ID；{@code GLOBAL} 时忽略
     * @param reason  豁免原因（建议填写，便于回溯）
     */
    public WhitelistEntry add(String word, String scene, String scope, long ownerId, String reason) {
        if (word == null || word.trim().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "豁免词不能为空");
        }
        String s = (scene == null || scene.isBlank()) ? WhitelistEntry.SCENE_ANY : scene.trim().toUpperCase();
        String sc = (scope == null || scope.isBlank()) ? WhitelistEntry.SCOPE_GLOBAL : scope.trim().toUpperCase();
        if (!WhitelistEntry.SCENE_ANY.equals(s)) {
            // 场景必须能被枚举识别，否则该条目永远不会生效（covers 对未知场景返回 false）
            try {
                ContentScene.valueOf(s);
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "未知场景: " + s + "（可用: * / NICKNAME / CAPTION / COMMENT）");
            }
        }
        if (!WhitelistEntry.SCOPE_GLOBAL.equals(sc) && !WhitelistEntry.SCOPE_USER.equals(sc)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未知范围: " + sc + "（可用: GLOBAL / USER）");
        }
        if (WhitelistEntry.SCOPE_USER.equals(sc) && ownerId <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "scope=USER 时必须指定 ownerId");
        }
        long owner = WhitelistEntry.SCOPE_USER.equals(sc) ? ownerId : 0L;
        WhitelistEntry saved = repository.upsert(word.trim(), s, sc, owner, reason);
        triggerReload(); // 后台异步重建，与词库一致；HTTP 线程不阻塞
        return saved;
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

    public List<WhitelistEntry> list(int limit, long offset) {
        return repository.findAll(limit, offset);
    }

    public long count() {
        return repository.count();
    }

    /**
     * 后台异步重建（单飞）：admin 变更、定时检测、跨实例 epoch 失效广播统一走这里。
     *
     * <p><b>单飞语义</b>：重建进行中时后续触发直接返回，由进行中的那次重建读最新 DB 并
     * 比对指纹追平（最多 3 轮），保证「最后写的那条豁免」一定进入下一个生效快照，
     * 既不漏也不重复全量重建。异常仅记日志、保留旧索引——旧豁免集合总比空集合安全。</p>
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
                log.warn("白名单后台刷新失败（保留旧索引）: {}", e.getMessage());
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
}
