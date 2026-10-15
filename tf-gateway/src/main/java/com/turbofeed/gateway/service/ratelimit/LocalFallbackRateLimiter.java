package com.turbofeed.gateway.service.ratelimit;

import com.turbofeed.gateway.config.MediaProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户维度限流本地兜底（Redis 不可用时的降级实现）。
 *
 * <p><b>为什么需要它</b>：{@link UploadRateLimiter} 主路径依赖 Redis 做跨实例统一计数；
 * Redis 故障时若直接 fail-open（放行），等于把"防单用户/大流量刷接口"的全局闸整道拿掉——
 * 大流量下洪峰会直接冲存储/DB，是分布式部署下不该有的缺口。本兜底在 Redis 不可用时，
 * 用进程内固定窗口计数，仍能拦住<b>单实例</b>内单用户的刷接口行为（多实例不共享计数，
 * 是已知取舍，属 graceful degradation 而非全开）。</p>
 *
 * <p><b>业务维度隔离</b>：与 Redis 主路径一致，计数键用 {@code keyPrefix + userId}，使降级前后
 * upload / comment / like 各业务线计数互不混池（早期实现只传 userId，降级时所有业务线挤一个池，
 * 互不相关的接口会互相误拦）。</p>
 *
 * <p><b>阈值下探</b>：单实例阈值 = {@code perUser / max(fallbackInstanceEstimate, 1)}。多实例下 Redis
 * 不可用走本地兜底时，若不下调，全局实际限流会被放大成 {@code perUser × N}（过松，等于变相放开）；
 * 按估算实例数下探，使兜底口径尽量贴近「全局 perUser」的设计意图。{@code fallbackInstanceEstimate=1}
 * 时退化为不过滤（perUser 原值）。</p>
 *
 * <p><b>容量</b>：{@code ConcurrentHashMap} 按 {@code keyPrefix+userId} 存窗口计数；窗口滚动时旧条目
 * 被新值覆盖，并对长期不活跃（超过一个窗口）的条目做阈值清理，避免内存无界增长。</p>
 *
 * <p><b>可观测</b>：实际拒绝计入 {@code tf_upload_ratelimit_fallback_denied_total}，配合
 * {@link UploadRateLimiter} 的 {@code tf_upload_ratelimit_fallback_total}（兜底进入次数），可观测
 * 降级触发率与拒绝量——Redis 持续不可用 = 上游大故障，触发率长期 >0 应告警甚至熔断，不能裸奔。</p>
 */
@Slf4j
@Component
public class LocalFallbackRateLimiter {

    /** 每用户窗口计数：slot[0]=count，slot[1]=窗口起始秒（epoch second）。 */
    private final ConcurrentHashMap<String, long[]> counters = new ConcurrentHashMap<>();
    private final MediaProperties properties;
    private final Counter fallbackDeniedTotal;

    public LocalFallbackRateLimiter(MediaProperties properties, MeterRegistry registry) {
        this.properties = properties;
        this.fallbackDeniedTotal = Counter.builder("tf_upload_ratelimit_fallback_denied_total")
                .description("本地兜底限流实际拒绝次数（Redis 不可用期间，按业务维度隔离计数）")
                .register(registry);
    }

    /**
     * 兼容旧调用：无业务前缀时退化为 userId 维度（兜底隔离退化为单池，仅用于未传前缀的残留调用点）。
     *
     * @return true 放行；false 已达单实例单机阈值
     */
    public boolean tryAcquire(String userId) {
        return tryAcquire(userId, "");
    }

    /**
     * 进程内固定窗口判定（带业务维度隔离，与 Redis 主路径 keyPrefix 对齐）。
     *
     * @param userId     归属用户
     * @param keyPrefix  业务维度前缀（如 {@code rl:upload:} / {@code rl:comment:} / {@code rl:like:}），空串表示未隔离
     * @return true 放行；false 已达单实例单机阈值
     */
    public boolean tryAcquire(String userId, String keyPrefix) {
        int perUser = properties.getRateLimit().getPerUser();
        int estimate = Math.max(properties.getRateLimit().getFallbackInstanceEstimate(), 1);
        int effectivePerUser = perUser <= 0 ? 0 : Math.max(1, perUser / estimate);
        int windowSeconds = properties.getRateLimit().getWindowSeconds();
        if (effectivePerUser <= 0) {
            return true; // 配置关闭用户维度限流（或下探后为 0，放行）
        }
        long nowSec = System.currentTimeMillis() / 1000;
        // compute 对同一 key 原子：窗口过期则重置为 [1, now]，否则累加；不同 key 并发安全
        long[] slot = counters.compute(keyPrefix + userId, (k, v) -> {
            if (v == null || nowSec - v[1] >= windowSeconds) {
                return new long[]{1, nowSec};
            }
            v[0] = v[0] + 1;
            return v;
        });
        // 近似清理：规模过大时移除已过一个窗口的陈旧条目，防止内存无界增长
        if (counters.size() > 50_000) {
            counters.entrySet().removeIf(e -> nowSec - e.getValue()[1] >= windowSeconds);
        }
        boolean allowed = slot[0] <= effectivePerUser;
        if (!allowed) {
            log.warn("本地兜底限流触发（Redis 不可用期间）: prefix={}, userId={}, count={}, effectivePerUser={}, window={}s",
                    keyPrefix, userId, slot[0], effectivePerUser, windowSeconds);
            fallbackDeniedTotal.increment();
        }
        return allowed;
    }
}
