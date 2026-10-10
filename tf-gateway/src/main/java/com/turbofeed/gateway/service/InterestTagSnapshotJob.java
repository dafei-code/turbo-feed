package com.turbofeed.gateway.service;

import com.turbofeed.gateway.repository.InterestTagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户兴趣标签快照作业（抖音式「用户画像」耐久层落地）。
 *
 * <p><b>为什么在网关</b>：兴趣画像主计算在 feed-engine（Redis Hash {@code tf:user:interest:{userId}}
 * 长期层 + {@code tf:user:interest:recent:{userId}} 短期层，半衰期衰减），但引擎无 DataSource。
 * 网关同时具备 Redis + ShardingSphere JDBC，故由本作业定时 SCAN 这两个 Redis 键空间，
 * 把每个用户的标签权重 upsert 到 {@code interest_tag} 表（按 layer 区分长/短期），作耐久备份 +
 * 离线特征源（A1 双塔可直接读用户长期兴趣）。</p>
 *
 * <p><b>实现</b>：用 {@code SCAN}（非 {@code KEYS}，避免阻塞 Redis）遍历画像键；从键名解析 userId 与层；
 * 读 Hash 过滤 {@code __ts__} 时间戳哨兵，逐标签解析衰减后权重 upsert。整体 fail-open：
 * 单键/单标签异常跳过，绝不因快照失败影响浏览或网关启动。</p>
 *
 * <p><b>默认</b>：每日 04:15 跑（{@code cron} 可配）；亦可手动 {@link #snapshot()} 触发（e2e 探针用）。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class InterestTagSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(InterestTagSnapshotJob.class);

    private static final String LONG_PREFIX = "tf:user:interest:";
    private static final String SHORT_PREFIX = "tf:user:interest:recent:";
    private static final String TS_FIELD = "__ts__";

    private final StringRedisTemplate redis;
    private final InterestTagRepository repository;
    private final boolean enabled;

    public InterestTagSnapshotJob(StringRedisTemplate redis,
                                  InterestTagRepository repository,
                                  @Value("${turbofeed.feed.interest-tag.snapshot.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.repository = repository;
        this.enabled = enabled;
    }

    /** 定时快照（默认每日 04:15）。fail-open：整体异常只告警。 */
    @Scheduled(cron = "${turbofeed.feed.interest-tag.snapshot.cron:0 15 4 * * ?}")
    public void scheduledSnapshot() {
        if (!enabled) {
            return;
        }
        try {
            int n = snapshot();
            log.info("兴趣标签快照完成：处理用户/层条目数={}", n);
        } catch (Exception e) {
            log.warn("兴趣标签快照（定时）失败（fail-open）: {}", e.getMessage());
        }
    }

    /** 手动触发快照（e2e 探针）。返回处理的 (user,tag,layer) 条目数。 */
    public int snapshot() {
        int total = 0;
        total += scanAndPersist(LONG_PREFIX, "long");
        total += scanAndPersist(SHORT_PREFIX, "short");
        return total;
    }

    private int scanAndPersist(String prefix, String layer) {
        int count = 0;
        List<String> keys = scanKeys(prefix + "*");
        HashOperations<String, Object, Object> hashOps = redis.opsForHash();
        for (String key : keys) {
            Long userId = parseUserId(key, prefix);
            if (userId == null) {
                continue;
            }
            try {
                Map<Object, Object> entries = hashOps.entries(key);
                for (Map.Entry<Object, Object> e : entries.entrySet()) {
                    String field = String.valueOf(e.getKey());
                    if (TS_FIELD.equals(field)) {
                        continue; // 时间戳哨兵，非兴趣标签
                    }
                    String raw = String.valueOf(e.getValue());
                    double weight = parseWeight(raw);
                    if (Double.isNaN(weight)) {
                        continue;
                    }
                    try {
                        repository.upsert(userId, field, weight, layer);
                        count++;
                    } catch (Exception ex) {
                        log.warn("兴趣标签 upsert 失败（跳过）: userId={}, tag={}, {}", userId, field, ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                log.warn("兴趣标签读 Hash 失败（跳过）: key={}, {}", key, ex.getMessage());
            }
        }
        return count;
    }

    private Long parseUserId(String key, String prefix) {
        String suffix = key.substring(prefix.length());
        if (suffix.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private static double parseWeight(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignore) {
            return Double.NaN;
        }
    }

    /** SCAN 遍历键（避免 KEYS 阻塞 Redis）。fail-open：异常返回空列表。 */
    private List<String> scanKeys(String pattern) {
        // 注：本版本 Spring Data Redis 中 ScanOptions.scanOptions() 已标记 @Deprecated 但无替代工厂，
        // 为兼容保留；属良性编译警告，不影响功能。
        ScanOptions opts = ScanOptions.scanOptions().match(pattern).count(500).build();
        try {
            return redis.execute((org.springframework.data.redis.connection.RedisConnection conn) -> {
                List<String> keys = new ArrayList<>();
                try (var cursor = conn.scan(opts)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return keys;
            });
        } catch (Exception e) {
            log.warn("SCAN {} 失败（fail-open）: {}", pattern, e.getMessage());
            return List.of();
        }
    }
}
