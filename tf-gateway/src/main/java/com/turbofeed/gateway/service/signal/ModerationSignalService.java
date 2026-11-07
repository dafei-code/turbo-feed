package com.turbofeed.gateway.service.signal;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.Disposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核信号外供 KV（P2-1，抖音式「审核与推荐解耦」）。
 *
 * <p><b>生产者</b>：turbo-feed 在每处「状态确认」后把审核信号写成 Redis Hash，
 * 命名空间 {@code tf:mod:}（可被 {@link MediaProperties.Signal#getNamespace()} 覆盖）。
 * <b>消费者</b>：上游推荐系统直读这些 KV 做召回过滤（INTERCEPT/MONITOR）、精排降权
 * （内容级 DEMOTE + 作者级低健康分），不直连审核 MySQL。</p>
 *
 * <p><b>fail-open</b>：所有写/删操作 catch 后仅记日志、绝不抛异常、不阻断审核主流程——
 * 审核结果已落 MySQL，KV 只是给上游的加速副本，丢失不影响审核正确性（上游 miss 回源 MySQL）。</p>
 *
 * <p><b>TTL</b>：内容处置信号随内容生命周期（默认 30d 可刷新）；账号健康分/举报人信用为刷新式
 * （每次写覆盖、不设备过期），由 {@link MediaProperties.Signal} 控制（0=不过期）。</p>
 *
 * <p><b>解除闭环（ADR-0014）</b>：内容级信号不能只写不删——REVIEW 产生的 MONITOR 若在人审
 * 判无违规后留着，内容会在默认 30 天 TTL 内一直被召回层/搜索层剔除，表现为「审核通过了却搜不到」。
 * 因此本类同时提供 {@link #clearMediaDisposition(String)}，由人审判无违规与改描述 PASS 路径调用。
 * 注意 <b>自动审核 APPROVED 不清信号</b>：先发后审正靠 MONITOR 做审核窗口隔离。</p>
 */
@Service
public class ModerationSignalService {

    /**
     * 显式日志与构造器（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    private static final Logger log = LoggerFactory.getLogger(ModerationSignalService.class);

    /**
     * 条件解除 DEMOTE 的原子脚本（ADR-0015）：{@code HGET action} 与 {@code DEL} 在同一次
     * EVAL 内完成，Redis 单线程执行保证「读到 DEMOTE 后另一路把信号升级成 MONITOR/INTERCEPT」
     * 的窗口不存在——否则改一次描述就可能抹掉人审隔离或下架结论。
     *
     * <p>只访问 {@code KEYS[1]} 一个键，满足 Redis Cluster 单 slot 约束；Spring Data Redis
     * 自动处理 EVALSHA 的 NOSCRIPT 回退。返回 1=已删除 / 0=未删除（含 action 缺失或不是 DEMOTE）。</p>
     */
    private static final DefaultRedisScript<Long> CLEAR_DEMOTE_SCRIPT = new DefaultRedisScript<>(
            "local action = redis.call('HGET', KEYS[1], 'action') " +
                    "if action == ARGV[1] then return redis.call('DEL', KEYS[1]) end " +
                    "return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final MediaProperties mediaProperties;

    public ModerationSignalService(StringRedisTemplate redisTemplate, MediaProperties mediaProperties) {
        this.redisTemplate = redisTemplate;
        this.mediaProperties = mediaProperties;
    }

    private String mediaKey(String postId) {
        return mediaProperties.getSignal().getNamespace() + "media:" + postId;
    }

    private String healthKey(long userId) {
        return mediaProperties.getSignal().getNamespace() + "account:" + userId;
    }

    private String creditKey(long userId) {
        return mediaProperties.getSignal().getNamespace() + "reporter:" + userId;
    }

    /** 写内容处置信号（INTERCEPT/MONITOR/DEMOTE 都写；后者是内容级轻降权，非确认违规）。 */
    public void publishMediaDisposition(String postId, Disposition action, ViolationSeverity severity, ViolationSource source) {
        if (postId == null || postId.isBlank()) {
            return;
        }
        try {
            String key = mediaKey(postId);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("action", action.name());
            m.put("severity", severity.name());
            m.put("source", source.name());
            m.put("ts", Long.toString(Instant.now().getEpochSecond()));
            redisTemplate.opsForHash().putAll(key, m);
            long ttl = mediaProperties.getSignal().getMediaTtlSeconds();
            if (ttl > 0) {
                redisTemplate.expire(key, Duration.ofSeconds(ttl));
            }
        } catch (Exception e) {
            log.warn("写内容处置信号失败（fail-open，不影响主流程）: postId={}, action={}, {}", postId, action, e.getMessage());
        }
    }

    /**
     * 解除内容处置信号（ADR-0014）：整键删除 {@code tf:mod:media:{postId}}，让引擎召回层、
     * 搜索层与精排降权同时恢复「无信号」状态。
     *
     * <p><b>调用时机（必须严格限定）</b>：{@code CONTENT_REVIEW} 人审判无违规、改描述后裁定 PASS。
     * <b>不要在自动审核 APPROVED 时调用</b>——先发后审靠 MONITOR 做审核窗口隔离，提前清掉等于
     * 让待复核内容直接进公域。</p>
     *
     * <p><b>fail-open</b>：删除失败只记日志不抛——KV 是加速副本，最坏是信号残留到 TTL 到期，
     * 不影响审核结论本身（DB 状态才是真值）。blank postId 直接跳过，避免误删同名前缀键。</p>
     */
    public void clearMediaDisposition(String postId) {
        if (postId == null || postId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(mediaKey(postId));
            log.info("解除内容处置信号: postId={}", postId);
        } catch (Exception e) {
            log.warn("解除内容处置信号失败（fail-open，信号将随 TTL 自动到期）: postId={}, {}",
                    postId, e.getMessage());
        }
    }

    /**
     * 条件解除：仅当当前信号是 {@link Disposition#DEMOTE}（内容级轻降权）时删除整键。
     *
     * <p><b>用途：改描述后裁定 PASS。</b>此时文本已重新合规，DEMOTE 该解除；但
     * MONITOR（先发后审的人审隔离）与 INTERCEPT（举报/复审确认违规的下架）都不是
     * 「文本质量」的产物，只能由各自的业务流程解除，绝不能因为改了描述就抹掉——
     * 否则作者改一次文案即可绕过人审隔离甚至下架。</p>
     *
     * <p><b>原子性（ADR-0015）</b>：早期实现是 Java 侧「{@code HGET} 判断再 {@code DEL}」两步，
     * 并发下若另一路在这两步之间把 DEMOTE 升级为 MONITOR/INTERCEPT，改描述 PASS 会误删升级后的
     * 信号，等于用一次改文案绕过人审隔离或下架。现改为 {@link #CLEAR_DEMOTE_SCRIPT} 一次 EVAL
     * 完成条件判断与删除，脚本只碰一个键、集群兼容。</p>
     *
     * <p>fail-open：脚本执行异常只记日志不抛；信号最坏残留到 TTL 到期，不影响审核结论本身。</p>
     */
    public void clearDemotionIfPresent(String postId) {
        if (postId == null || postId.isBlank()) {
            return;
        }
        try {
            String key = mediaKey(postId);
            Long deleted = redisTemplate.execute(CLEAR_DEMOTE_SCRIPT, List.of(key), Disposition.DEMOTE.name());
            if (deleted != null && deleted > 0L) {
                log.info("内容重新合规→解除 DEMOTE 轻降权信号: postId={}", postId);
            }
        } catch (Exception e) {
            log.warn("条件解除 DEMOTE 信号失败（fail-open，信号将随 TTL 自动到期）: postId={}, {}",
                    postId, e.getMessage());
        }
    }

    /** 写账号健康分信号（刷新式，默认不过期）。 */
    public void publishAccountHealth(long userId, int score, String tier) {
        try {
            String key = healthKey(userId);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("score", Integer.toString(score));
            m.put("tier", tier);
            m.put("ts", Long.toString(Instant.now().getEpochSecond()));
            redisTemplate.opsForHash().putAll(key, m);
            long ttl = mediaProperties.getSignal().getAccountTtlSeconds();
            if (ttl > 0) {
                redisTemplate.expire(key, Duration.ofSeconds(ttl));
            }
        } catch (Exception e) {
            log.warn("写账号健康分信号失败（fail-open，不影响主流程）: userId={}, score={}, tier={}, {}", userId, score, tier, e.getMessage());
        }
    }

    /** 写举报人信用信号（刷新式，默认不过期）。 */
    public void publishReporterCredit(long userId, int score, boolean banned) {
        try {
            String key = creditKey(userId);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("score", Integer.toString(score));
            m.put("banned", Boolean.toString(banned));
            m.put("ts", Long.toString(Instant.now().getEpochSecond()));
            redisTemplate.opsForHash().putAll(key, m);
            long ttl = mediaProperties.getSignal().getReporterTtlSeconds();
            if (ttl > 0) {
                redisTemplate.expire(key, Duration.ofSeconds(ttl));
            }
        } catch (Exception e) {
            log.warn("写举报人信用信号失败（fail-open，不影响主流程）: userId={}, score={}, {}", userId, score, e.getMessage());
        }
    }

    /** 读单帖处置信号（miss 返回 null，上游回源 MySQL）。 */
    public ModerationSignalView.DispositionView getMediaDisposition(String postId) {
        if (postId == null || postId.isBlank()) {
            return null;
        }
        try {
            String key = mediaKey(postId);
            HashOperations<String, String, String> h = redisTemplate.opsForHash();
            String action = h.get(key, "action");
            if (action == null) {
                return null;
            }
            return new ModerationSignalView.DispositionView(
                    action,
                    h.get(key, "severity"),
                    h.get(key, "source"),
                    parseLong(h.get(key, "ts"), 0L));
        } catch (Exception e) {
            log.warn("读内容处置信号失败（回源 MySQL）: postId={}, {}", postId, e.getMessage());
            return null;
        }
    }

    /**
     * 批量读处置信号（推荐召回过滤用）：Redis 集群下不同 postId 落在不同 slot，
     * 不能 multiGet，逐个读（O(N)，N 为召回候选数，通常百级，可接受）。miss 的键不进结果。
     */
    public Map<String, ModerationSignalView.DispositionView> batchGetMediaDisposition(List<String> postIds) {
        Map<String, ModerationSignalView.DispositionView> result = new LinkedHashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        for (String postId : postIds) {
            ModerationSignalView.DispositionView v = getMediaDisposition(postId);
            if (v != null) {
                result.put(postId, v);
            }
        }
        return result;
    }

    /** 读账号健康分信号（miss 返回 null）。 */
    public ModerationSignalView.HealthView getAccountHealth(long userId) {
        try {
            String key = healthKey(userId);
            HashOperations<String, String, String> h = redisTemplate.opsForHash();
            String score = h.get(key, "score");
            if (score == null) {
                return null;
            }
            return new ModerationSignalView.HealthView(
                    Integer.parseInt(score),
                    h.get(key, "tier"),
                    parseLong(h.get(key, "ts"), 0L));
        } catch (Exception e) {
            log.warn("读账号健康分信号失败（回源 MySQL）: userId={}, {}", userId, e.getMessage());
            return null;
        }
    }

    /** 读举报人信用信号（miss 返回 null）。 */
    public ModerationSignalView.CreditView getReporterCredit(long userId) {
        try {
            String key = creditKey(userId);
            HashOperations<String, String, String> h = redisTemplate.opsForHash();
            String score = h.get(key, "score");
            if (score == null) {
                return null;
            }
            return new ModerationSignalView.CreditView(
                    Integer.parseInt(score),
                    Boolean.parseBoolean(h.get(key, "banned")),
                    parseLong(h.get(key, "ts"), 0L));
        } catch (Exception e) {
            log.warn("读举报人信用信号失败（回源 MySQL）: userId={}, {}", userId, e.getMessage());
            return null;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
