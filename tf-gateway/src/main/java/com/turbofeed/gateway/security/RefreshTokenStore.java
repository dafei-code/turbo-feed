package com.turbofeed.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 刷新令牌的服务端有状态存储（解决纯无状态 JWT 的「无法主动失效 / 无法轮换」短板）。
 *
 * <p><b>两套 key</b>：
 * <ol>
 *   <li>{@code turbofeed:jwt:refresh:{jti}} = uid，TTL = 刷新令牌剩余有效期。
 *       校验时比对 uid 防「令牌错配」，吊销即 DEL。</li>
 *   <li>{@code turbofeed:jwt:user:{uid}} = Set<jti>，记录该用户当前所有活跃令牌的 jti
 *       （<b>访问令牌 + 刷新令牌都登记</b>，见 {@link #registerAccessToken}）。
 *       用于「一处登出全端失效」与「角色变更 / 封禁联动吊销」（step 4）：SMEMBERS 批量吊销。</li>
 * </ol></p>
 *
 * <p><b>集合 TTL（修复内存泄漏）</b>：{@code user:{uid}} 集合本身设 TTL = 刷新令牌有效期
 * （最长生命周期），每次登记续期。这样集合随最后一次活跃令牌自然消亡，
 * 不会因 RT 自然过期而永久残留失效 jti（旧实现无 TTL，会缓慢泄漏）。</p>
 *
 * <p><b>批量吊销覆盖访问令牌</b>：{@link #revokeAllForUser} 对集合内每个 jti 调
 * {@link TokenBlacklist#revokeJti} 进黑名单——访问令牌经黑名单闸口（{@code JwtAuthenticationFilter}）
 * 立即失效，刷新令牌另删 RT key。于是「角色变更 / 封禁 / 全端登出」对访问令牌也是<b>即时生效</b>
 * （旧实现只吊销 RT，AT 要等 ≤30min 自然过期）。</p>
 *
 * <p><b>Fail-open（与 TokenBlacklist 一致）</b>：Redis 不可用时，store / revoke 尽力而为仅记日志；
 * 批量吊销返回 0（视为无效，逼客户端重新登录）。理由：刷新令牌是「增强可用性」的辅助通道，
 * 不能因 Redis 抖动把登录链路整体拖垮；最坏情况退化为「无刷新，访问令牌到期需重新登录」，行为可接受。</p>
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String RT_PREFIX = "turbofeed:jwt:refresh:";
    private static final String USER_PREFIX = "turbofeed:jwt:user:";

    private final StringRedisTemplate redisTemplate;
    private final TokenBlacklist tokenBlacklist;
    /** 用户集合的 TTL = 刷新令牌有效期（最长生命周期），保证 RT jti 在被吊销前不被集合过期丢弃。 */
    private final long userSetTtlSeconds;

    public RefreshTokenStore(StringRedisTemplate redisTemplate, TokenBlacklist tokenBlacklist,
                             com.turbofeed.gateway.config.JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.tokenBlacklist = tokenBlacklist;
        this.userSetTtlSeconds = jwtProperties.getRefreshExpireSeconds();
    }

    /** 落库一枚刷新令牌：记录 RT key（uid，TTL=rtTtl），并把 jti 加入该用户的活跃集合。 */
    public void storeRefresh(String jti, String uid, long rtTtl) {
        try {
            redisTemplate.opsForValue().set(RT_PREFIX + jti, uid, Duration.ofSeconds(rtTtl));
            addToUserSet(uid, jti);
        } catch (Exception e) {
            log.warn("刷新令牌存储失败（已尽力而为，刷新通道可能不可用）: {}", e.getMessage());
        }
    }

    /** 登记一枚访问令牌的 jti 到用户活跃集合（不带独立 key，失效靠黑名单）。TTL 由集合统一兜底。 */
    public void registerAccessToken(String jti, String uid) {
        addToUserSet(uid, jti);
    }

    /** 该 jti 是否仍为有效刷新令牌，且归属给定 uid。Redis 异常时返回 false。 */
    public boolean isValid(String jti, String uid) {
        try {
            String stored = redisTemplate.opsForValue().get(RT_PREFIX + jti);
            return uid.equals(stored);
        } catch (Exception e) {
            log.warn("刷新令牌校验失败，降级为无效: {}", e.getMessage());
            return false;
        }
    }

    /** 吊销单枚刷新令牌：删除 RT key，并从用户活跃集合移除。 */
    public void revoke(String jti, String uid) {
        try {
            redisTemplate.delete(RT_PREFIX + jti);
            redisTemplate.opsForSet().remove(USER_PREFIX + uid, jti);
        } catch (Exception e) {
            log.warn("刷新令牌吊销失败（已尽力而为）: {}", e.getMessage());
        }
    }

    /**
     * 吊销某用户的全部令牌（全端登出 / 角色变更联动吊销 / 封禁）。
     *
     * <p>对集合内每个 jti 进黑名单（覆盖访问令牌），并删除对应 RT key；最后删集合。
     * 返回实际处理的 jti 数。</p>
     */
    public long revokeAllForUser(String uid) {
        try {
            Set<String> jtis = redisTemplate.opsForSet().members(USER_PREFIX + uid);
            if (jtis == null || jtis.isEmpty()) {
                return 0;
            }
            for (String jti : jtis) {
                // 黑名单让访问令牌立即失效；刷新令牌 key 一并删除（双保险）。
                tokenBlacklist.revokeJti(jti, userSetTtlSeconds);
                redisTemplate.delete(RT_PREFIX + jti);
            }
            redisTemplate.delete(USER_PREFIX + uid);
            return jtis.size();
        } catch (Exception e) {
            log.warn("用户令牌批量吊销失败（已尽力而为）: {}", e.getMessage());
            return 0;
        }
    }

    /** 把 jti 加入用户活跃集合，并续期集合 TTL（修复集合无过期导致的泄漏）。 */
    private void addToUserSet(String uid, String jti) {
        try {
            redisTemplate.opsForSet().add(USER_PREFIX + uid, jti);
            redisTemplate.expire(USER_PREFIX + uid, Duration.ofSeconds(userSetTtlSeconds));
        } catch (Exception e) {
            log.warn("用户活跃集合登记失败（已尽力而为）: {}", e.getMessage());
        }
    }
}
