package com.turbofeed.gateway.security;

import com.turbofeed.gateway.config.SecurityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><b>Redis 不可用时的行为（显式开关，不再隐式二选一）</b>：
 * <ul>
 *   <li><b>默认 fail-closed</b>（{@code turbofeed.security.token-blacklist-fail-open-on-error=false}）：
 *       {@link #isRevoked} 查询异常时按「已吊销」返回 true → 请求被拒。
 *       理由：查不到黑名单就无法证明令牌未被吊销，而黑名单存在的唯一目的就是止损；
 *       放行会让"已登出 / 已风控"的令牌在 Redis 抖动期间继续可用，且日志监控上完全不可见。</li>
 *   <li><b>显式 fail-open</b>（开关置 true）：异常时返回 false 放行，但每次降级都会打 WARN 并累加指标
 *       {@code turbofeed.security.blacklist.error}{@code mode=open}，是可观测的受控降级。</li>
 * </ul>
 * 两种模式都必须在《威胁模型与降级矩阵》（{@code docs/architecture/threat-model-degradation.md}）中登记，
 * 不允许"代码里悄悄选一个"。</p>
 *
 * <p><b>写入侧（revoke）为什么不抛异常</b>：登出是用户主动操作，客户端通常不处理响应，
 * 抛异常也不会带来重试；但失败必须<b>可见</b>——因此按模式打 ERROR/WARN 并累加
 * {@code mode=write} 指标，而不是静默吞掉。</p>
 *
 * <p><b>边界</b>：本类只负责「jti 是否被吊销」这一件事。签发带 jti 的令牌在 {@link JwtUtil}，
 * 查黑名单在 {@link JwtAuthenticationFilter}（验签后的统一信任闸口）。</p>
 */
@Component
public class TokenBlacklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklist.class);

    /** 黑名单 key 前缀；与项目其它 Redis key 保持 {@code turbofeed:} 命名空间一致。 */
    private static final String BLACKLIST_PREFIX = "turbofeed:jwt:blacklist:";

    /** 指标名：黑名单依赖 Redis 出错次数（tag mode = closed / open / write）。 */
    private static final String METRIC_ERROR = "turbofeed.security.blacklist.error";

    /** 降级日志节流：每 N 次打一条，避免 Redis 抖动时每个请求刷一条日志。 */
    private static final int LOG_EVERY = 50;

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final SecurityProperties securityProperties;
    private final Clock clock;

    private final Counter closedCounter;
    private final Counter openCounter;
    private final Counter writeCounter;
    private final AtomicLong logSeq = new AtomicLong();

    public TokenBlacklist(StringRedisTemplate redisTemplate, JwtUtil jwtUtil,
                          SecurityProperties securityProperties, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
        this.securityProperties = securityProperties;
        this.clock = Clock.systemUTC();
        this.closedCounter = Counter.builder(METRIC_ERROR).tags(Tags.of("mode", "closed"))
                .description("黑名单查询失败且按 fail-closed 拒绝请求的次数").register(meterRegistry);
        this.openCounter = Counter.builder(METRIC_ERROR).tags(Tags.of("mode", "open"))
                .description("黑名单查询失败且按显式开关 fail-open 放行的次数").register(meterRegistry);
        this.writeCounter = Counter.builder(METRIC_ERROR).tags(Tags.of("mode", "write"))
                .description("令牌吊销写入 Redis 失败的次数（登出/踢人未真正生效）").register(meterRegistry);
    }

    /**
     * 吊销令牌：解析出 jti 与剩余有效期，写入黑名单（TTL = 剩余有效期）。
     *
     * <p>已过期令牌（剩余有效期 ≤ 0）无需写入（自然过期即失效）。
     * 令牌本身不可解析时视为无需吊销（不抛异常，避免"用过期令牌登出"报错）。
     * Redis 写入失败时<b>不静默</b>：按当前模式打 ERROR（fail-closed）/ WARN（fail-open）并累加指标。</p>
     */
    public void revokeToken(String token) {
        JwtUtil.RevocableToken rt;
        try {
            rt = jwtUtil.parseForRevoke(token);
        } catch (Exception e) {
            // 令牌不可解析（已过期 / 被篡改 / 旧格式）：本就不可用，无需吊销
            log.debug("令牌解析失败，跳过吊销: {}", e.getMessage());
            return;
        }
        long remainingSec = rt.expireAtEpochSec() - clock.millis() / 1000;
        if (remainingSec <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue()
                    .set(BLACKLIST_PREFIX + rt.jti(), "", Duration.ofSeconds(remainingSec));
            log.info("已吊销令牌 jti={}，黑名单 TTL={}s", rt.jti(), remainingSec);
        } catch (Exception e) {
            writeCounter.increment();
            if (failOpenOnError()) {
                log.warn("令牌吊销写入失败（显式 fail-open，登出返回成功但令牌仍可用）: {}", e.getMessage());
            } else {
                log.error("令牌吊销写入失败（fail-closed 模式下属安全事件：用户已登出但令牌仍可用）: {}", e.getMessage());
            }
        }
    }

    /**
     * 令牌是否已被吊销。
     *
     * @param jti 令牌唯一标识；为 null / 空（旧格式令牌）时视为未吊销
     * @return true 表示在黑名单中、或<b>无法证明未被吊销</b>（Redis 故障且当前为 fail-closed），应拒绝
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
        } catch (Exception e) {
            return onRedisError(e);
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
            writeCounter.increment();
            if (failOpenOnError()) {
                log.warn("按 jti 吊销失败（显式 fail-open）: {}", e.getMessage());
            } else {
                log.error("按 jti 吊销失败（fail-closed 模式下踢人未生效）: {}", e.getMessage());
            }
        }
    }

    /** Redis 查询失败时的显式降级决策：fail-closed 拒绝 / 显式开关下 fail-open 放行。 */
    private boolean onRedisError(Exception e) {
        long seq = logSeq.incrementAndGet();
        boolean throttled = (seq % LOG_EVERY == 1) || seq == 1;
        if (failOpenOnError()) {
            openCounter.increment();
            if (throttled) {
                log.warn("黑名单查询失败 → 按显式配置 fail-open 放行（共 {} 次）：{}。"
                                + "注意：已登出/已风控的令牌在此期间仍可用。", seq, e.getMessage());
            }
            return false;
        }
        closedCounter.increment();
        if (throttled) {
            log.error("黑名单查询失败 → fail-closed 拒绝请求（共 {} 次）：{}。"
                            + "如 Redis 长时间故障，可临时置 turbofeed.security.token-blacklist-fail-open-on-error=true 降级。",
                    seq, e.getMessage());
        }
        return true;
    }

    private boolean failOpenOnError() {
        return securityProperties != null && securityProperties.isTokenBlacklistFailOpenOnError();
    }
}
