package com.turbofeed.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * JWT 单令牌吊销（黑名单），解决「即时登出 / 风控踢人 / 盗号止损」——纯无状态 JWT 天生做不到的事。
 *
 * <p><b>它解决什么</b>：HS256 无状态令牌仅靠 exp 过期，服务端无法主动让一枚<b>仍在有效期内</b>的令牌失效。
 * 用户改密码 / 被盗 / 被风控，旧令牌在 exp 之前仍可用。本类把令牌的 {@code jti} 写入 Redis 黑名单，
 * 过滤器层在验签通过后二次查黑名单，命中即拒绝。</p>
 *
 * <p><b>存储模型</b>：{@code turbofeed:jwt:blacklist:{jti} = ""}，TTL = 令牌<b>剩余有效期</b>。
 * 这样黑名单条目会随令牌自然过期自动清理，<b>无需额外 GC / 扫描</b>，也无需在配置里维护独立 TTL
 * （令牌有效期即黑名单生命周期上界，多存无意义）。</p>
 *
 * <p><b>Redis 不可用时的行为（Fail-Open）</b>：{@link #isRevoked} 查询异常时降级为「未吊销」，
 * {@link #revokeToken} 异常时仅记日志、不阻断登出。理由与 {@code AccountStateCache} 一致——
 * 吊销是「增强控制」，不能因为 Redis 抖动就把全站请求挡在门外（那会变成可用性事故）；
 *  worst case 退化为「令牌仍按 exp 自然过期」，与引入本机制前的行为一致。</p>
 *
 * <p><b>边界</b>：本类只负责「jti 是否被吊销」这一件事。签发带 jti 的令牌在 {@link JwtUtil}，
 * 查黑名单在 {@link JwtAuthenticationFilter}（验签后的统一信任闸口）。</p>
 */
@Component
public class TokenBlacklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklist.class);

    /** 黑名单 key 前缀；与项目其它 Redis key 保持 {@code turbofeed:} 命名空间一致。 */
    private static final String BLACKLIST_PREFIX = "turbofeed:jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final Clock clock;

    public TokenBlacklist(StringRedisTemplate redisTemplate, JwtUtil jwtUtil) {
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
        this.clock = Clock.systemUTC();
    }

    /**
     * 吊销令牌：解析出 jti 与剩余有效期，写入黑名单（TTL = 剩余有效期）。
     *
     * <p>已过期令牌（剩余有效期 ≤ 0）无需写入（自然过期即失效）。
     * Redis 异常时尽力而为、仅记日志，不抛异常——登出链路不应因 Redis 抖动失败。</p>
     */
    public void revokeToken(String token) {
        try {
            JwtUtil.RevocableToken rt = jwtUtil.parseForRevoke(token);
            long remainingSec = rt.expireAtEpochSec() - clock.millis() / 1000;
            if (remainingSec <= 0) {
                return;
            }
            redisTemplate.opsForValue()
                    .set(BLACKLIST_PREFIX + rt.jti(), "", Duration.ofSeconds(remainingSec));
            log.info("已吊销令牌 jti={}，黑名单 TTL={}s", rt.jti(), remainingSec);
        } catch (Exception e) {
            log.warn("令牌吊销失败（已尽力而为，不阻断登出）: {}", e.getMessage());
        }
    }

    /**
     * 令牌是否已被吊销。
     *
     * @param jti 令牌唯一标识；为 null / 空（旧格式令牌）时视为未吊销
     * @return true 表示在黑名单中，应拒绝
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
        } catch (Exception e) {
            log.warn("黑名单查询失败，降级为未吊销（fail-open）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 按 jti 直接吊销（不解析完整令牌），TTL 由调用方给定。
     *
     * <p>供 {@code RefreshTokenStore#revokeAllForUser} 批量吊销使用：角色变更 / 全端登出时，
     * 用户集合里的 jti 可能对应访问令牌或刷新令牌，统一进黑名单即可让二者立即失效
     * （刷新令牌另有独立 RT key，会被一并删除）。</p>
     */
    public void revokeJti(String jti, long ttlSeconds) {
        if (jti == null || jti.isEmpty()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "", Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("按 jti 吊销失败（已尽力而为）: {}", e.getMessage());
        }
    }
}
