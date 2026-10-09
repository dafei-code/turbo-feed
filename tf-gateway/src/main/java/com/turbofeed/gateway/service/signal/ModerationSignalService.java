package com.turbofeed.gateway.service.signal;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.Disposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <b>消费者</b>：上游推荐系统直读这些 KV 做召回过滤（INTERCEPT/MONITOR）与排序降权（低健康分），
 * 不直连审核 MySQL。</p>
 *
 * <p><b>fail-open</b>：所有写操作 catch 后仅记日志、绝不抛异常、不阻断审核主流程——
 * 审核结果已落 MySQL，KV 只是给上游的加速副本，丢失不影响审核正确性（上游 miss 回源 MySQL）。</p>
 *
 * <p><b>TTL</b>：内容处置信号随内容生命周期（默认 30d 可刷新）；账号健康分/举报人信用为刷新式
 * （每次写覆盖、不设备过期），由 {@link MediaProperties.Signal} 控制（0=不过期）。</p>
 */
@Service
public class ModerationSignalService {

    /**
     * 显式日志与构造器（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    private static final Logger log = LoggerFactory.getLogger(ModerationSignalService.class);

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

    /** 写内容处置信号（MONITOR/INTERCEPT 都写，二者都是已确认违规）。 */
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
