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
 * 白名单服务（误杀治理出口）—— 与 {@link SensitiveWordService} 同构的热更新模型。
 *
 * <h3>为什么与词库用同一套机制而不是各写一套</h3>
 * 两者形状完全一致：<b>数据量小、写少读多、读在写路径上、变更必须秒级生效</b>。
 * 因此同样采用「不可变快照 + {@link AtomicReference} 整体切换 + 零锁读」，
 * 同样用 {@code (COUNT, MAX(updated_at))} 指纹做 30s 变更检测，admin 写操作同步
 * {@link #reload()} 立即生效。<b>同一套心智模型，运营不需要记两套规则。</b></p>
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

    /** 启动时同步加载一次；失败仅告警（空集合 = 无豁免 = 回到改造前行为，安全）。 */
    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.error("白名单启动加载失败（本次按「无豁免」运行，不影响主流程）: {}", e.getMessage(), e);
        }
    }

    /** 30s 定时检测：指纹变化才重建索引。异常仅记日志、保留旧索引。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledReload() {
        try {
            WhitelistRepository.CheckSum current = repository.checkSum();
            if (current.equals(lastCheckSum)) {
                return;
            }
            int size = doReload(current);
            log.info("白名单定时刷新: count={}, entries={}", current.count(), size);
        } catch (Exception e) {
            log.warn("白名单定时刷新失败（保留旧索引）: {}", e.getMessage());
        }
    }

    /** 全量重建索引（admin 增删后立即调用）。返回加载的条目数。 */
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
        reload();
        return saved;
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

    public List<WhitelistEntry> list(int limit, long offset) {
        return repository.findAll(limit, offset);
    }

    public long count() {
        return repository.count();
    }
}
