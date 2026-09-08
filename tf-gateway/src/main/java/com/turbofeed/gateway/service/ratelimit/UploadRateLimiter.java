package com.turbofeed.gateway.service.ratelimit;

import com.turbofeed.gateway.config.MediaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 上传接口用户维度限流器（Redis 固定窗口，跨实例统一计数）。
 *
 * <p>与 {@code SentinelRateLimitConfig} 的分工：Sentinel 注解（@SentinelResource）管
 * 「单机 + 秒级」的机器维度（并发线程数 / QPS）；本类基于 user ID + Redis 管「跨实例 +
 * 时间窗」的用户维度（如每用户每 60s 最多 perUser 次），两者互补，不冲突。Redis 由
 * {@code spring-boot-starter-data-redis}（Lettuce）提供，{@link StringRedisTemplate}
 * 已由 Boot 自动装配。</p>
 *
 * <p><b>实现</b>：固定窗口。Lua 脚本原子执行 {@code INCR key} + 首增时 {@code EXPIRE key window}，
 * 返回窗口内累计计数；{@code count > perUser} 即拒绝。优点：一次往返、原子、无竞态；
 * 缺点：窗口边界突刺（最多 2× 阈值），UGC 防刷场景可接受。阈值与窗口取自
 * {@code MediaProperties.RateLimit}（turbofeed.media.rate-limit.per-user / window-seconds）。</p>
 *
 * <p><b>降级</b>：Redis 不可用时（本地克隆即跑未启动 Redis）以 warn 级别放行，避免限流组件
 * 故障导致上传全量 500；代价是限流暂时失效，符合「依赖故障不阻断主链路」的取舍。
 * {@code perUser <= 0} 时视为关闭用户维度限流，直接放行。</p>
 *
 * <p>key 命名：{@code rl:upload:{userId}}（前缀 rl 即 rate-limit，与媒体存储 key 前缀 media 区分）。</p>
 *
 * <p>调用位置：{@code MediaUploadService#upload} 方法体内、校验责任链之前
 * （@SentinelResource 注解在方法外层，先于方法体生效——即机器维度放行后轮到用户维度）；
 * 被拒时由调用方抛 {@code BizException(ErrorCode.RATE_LIMITED)}，与 Sentinel 被拒口径一致。</p>
 */
@Slf4j
@Service
public class UploadRateLimiter {

    private static final String KEY_PREFIX = "rl:upload:";

    /** 固定窗口 Lua：INCR 后若是首次则设置窗口 TTL，返回累计计数。 */
    private static final String LUA_INCR_WITH_TTL =
            "local c = redis.call('incr', KEYS[1])\n"
                    + "if c == 1 then redis.call('expire', KEYS[1], ARGV[2]) end\n"
                    + "return c";

    private final StringRedisTemplate redisTemplate;
    private final MediaProperties properties;

    public UploadRateLimiter(StringRedisTemplate redisTemplate, MediaProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 判定该用户本次上传是否放行。
     *
     * @param userId 归属用户（JWT 线程上下文取出的用户标识）
     * @return true 放行；false 已超限，调用方应按 RATE_LIMITED 拒绝
     */
    public boolean tryAcquire(String userId) {
        int perUser = properties.getRateLimit().getPerUser();
        int windowSeconds = properties.getRateLimit().getWindowSeconds();
        if (perUser <= 0) {
            return true; // 配置关闭用户维度限流
        }
        String key = KEY_PREFIX + userId;
        try {
            Long count = redisTemplate.execute(
                    RedisScript.of(LUA_INCR_WITH_TTL, Long.class),
                    Collections.singletonList(key),
                    String.valueOf(perUser),
                    String.valueOf(windowSeconds));
            boolean allowed = count != null && count <= perUser;
            if (!allowed) {
                log.warn("用户维度上传限流触发: userId={}, count={}, perUser={}, window={}s",
                        userId, count, perUser, windowSeconds);
            }
            return allowed;
        } catch (Exception e) {
            // Redis 不可用：降级放行，保证主链路可用（限流暂时失效，打 warn 便于排查）
            log.warn("用户维度限流 Redis 异常，降级放行: userId={}, {}", userId, e.getMessage());
            return true;
        }
    }
}
