package com.turbofeed.gateway.service.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 上传接口 Redis 限流器（方法骨架——<b>限流逻辑由使用者自行实现</b>）。
 *
 * <p>与 {@code SentinelRateLimitConfig} 的分工：Sentinel 注解（@SentinelResource）管
 * 「单机 + 秒级」的机器维度（并发线程数 / QPS）；本类基于 user ID + Redis 管「跨实例 +
 * 时间窗」的用户维度（如每用户每小时/每天上传张数），两者互补，不冲突。Redis 由
 * {@code spring-boot-starter-data-redis}（Lettuce）提供，{@link StringRedisTemplate}
 * 已由 Boot 自动装配，无需额外配置类。</p>
 *
 * <p>建议实现方案（二选一，按精度要求取舍）：</p>
 * <ul>
 *   <li><b>固定窗口</b>：{@code INCR key} 后 {@code EXPIRE key window}（首增时设置），
 *       超阈值拒绝。简单、一次往返（Lua 保证原子）；缺点是窗口边界突刺（2 倍流量）。</li>
 *   <li><b>滑动窗口</b>：ZADD 时间戳 + ZREMRANGEBYSCORE 清理 + ZCARD 计数，
 *       边界平滑；代价是每次请求多条命令（建议 Lua 打包）。</li>
 * </ul>
 *
 * <p>key 命名建议：{@code rl:upload:{userId}:{窗口}}（前缀 rl 即 rate-limit，
 * 与媒体存储 key 前缀 media 区分）；阈值建议取
 * {@code MediaProperties.RateLimit#getPerUser()}（turbofeed.media.rate-limit.per-user，默认 10）。</p>
 *
 * <p>调用位置（实现完成后接线）：{@code MediaUploadService#upload} 方法体内、
 * {@code validateBatch} 之前（@SentinelResource 注解在方法外层，先于方法体生效——
 * 即机器维度放行后轮到用户维度）；被拒时抛
 * {@code BizException(ErrorCode.RATE_LIMITED)}，与 Sentinel 被拒口径一致。</p>
 */
@Service
public class UploadRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UploadRateLimiter.class);

    private final StringRedisTemplate redisTemplate;

    public UploadRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 判定该用户本次上传是否放行。
     *
     * <p>TODO（使用者实现）：user ID + Redis 计数逻辑。
     * {@link #redisTemplate} 已就绪，直接用其 {@code opsForValue()} /
     * {@code opsForZSet()} / {@code execute(RedisScript)} 编写；实现前恒返回 true。</p>
     *
     * @param userId 归属用户（JWT 线程上下文取出的用户标识）
     * @return true 放行；false 已超限，调用方应按 RATE_LIMITED 拒绝
     */
    public boolean tryAcquire(String userId) {
        // TODO: userId + Redis 限流逻辑（建议 INCR+EXPIRE 固定窗口，或 Lua 滑动窗口）
        log.debug("Redis 限流未实现，恒放行: userId={}", userId);
        return true;
    }
}
