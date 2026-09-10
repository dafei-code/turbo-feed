package com.turbofeed.gateway.service.ratelimit;

import com.turbofeed.gateway.config.MediaProperties;
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
 * <p><b>语义对齐</b>：与 Redis 主路径保持同一套阈值（{@code per-user} / {@code window-seconds}）、
 * 同一固定窗口模型，使降级前后限流口径一致，只是计数从"全局"退化为"单实例"。</p>
 *
 * <p><b>容量</b>：{@code ConcurrentHashMap} 按 userId 存窗口计数；窗口滚动时旧条目被新值覆盖，
 * 并对长期不活跃（超过一个窗口）的条目做阈值清理，避免内存无界增长。</p>
 */
@Slf4j
@Component
public class LocalFallbackRateLimiter {

    /** 每用户窗口计数：slot[0]=count，slot[1]=窗口起始秒（epoch second）。 */
    private final ConcurrentHashMap<String, long[]> counters = new ConcurrentHashMap<>();
    private final MediaProperties properties;

    public LocalFallbackRateLimiter(MediaProperties properties) {
        this.properties = properties;
    }

    /**
     * 进程内固定窗口判定：窗口内累计不超过 perUser 则放行。
     *
     * @return true 放行；false 已达单实例单机阈值
     */
    public boolean tryAcquire(String userId) {
        int perUser = properties.getRateLimit().getPerUser();
        int windowSeconds = properties.getRateLimit().getWindowSeconds();
        if (perUser <= 0) {
            return true;
        }
        long nowSec = System.currentTimeMillis() / 1000;
        // compute 对同一 key 原子：窗口过期则重置为 [1, now]，否则累加；不同 key 并发安全
        long[] slot = counters.compute(userId, (k, v) -> {
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
        boolean allowed = slot[0] <= perUser;
        if (!allowed) {
            log.warn("本地兜底限流触发（Redis 不可用期间）: userId={}, count={}, perUser={}, window={}s",
                    userId, slot[0], perUser, windowSeconds);
        }
        return allowed;
    }
}
