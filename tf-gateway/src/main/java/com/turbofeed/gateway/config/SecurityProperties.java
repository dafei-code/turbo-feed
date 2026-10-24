package com.turbofeed.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全相关开关（{@code turbofeed.security.*}）。
 *
 * <p><b>为什么单独成类</b>：安全降级策略必须是<b>显式配置</b>，不能散落在代码里靠注释约定。
 * 尤其是"依赖中间件不可用时放行还是拒绝"这类二选一，一旦隐式就等于没人知道当前是哪种行为。
 * 本类的每一项都对应《威胁模型与降级矩阵》中的一格（{@code docs/architecture/threat-model-degradation.md}）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.security")
public class SecurityProperties {

    /**
     * Redis 不可用时，JWT 黑名单查询是 fail-open（放行）还是 fail-closed（拒绝）。
     *
     * <p><b>默认 false = fail-closed</b>：查不到黑名单就无法证明"该令牌未被吊销"，
     * 于是按"已吊销"处理并拒绝请求。选它作为默认，是因为黑名单存在的唯一目的就是
     * 「登出 / 风控踢人 / 盗号止损」——在 Redis 抖动期间放行，等于把安全闸口在最需要它的时候关掉，
     * 而这种放行在日志和监控上完全不可见（请求照常 200）。</p>
     *
     * <p><b>什么时候改成 true</b>：Redis 长时间故障且业务上宁可承担"已登出令牌仍可用"的风险，
     * 也要保住可用性。开启后每次降级都会打 WARN 并累加指标
     * {@code turbofeed.security.blacklist.error}（tag {@code mode=open}），是可观测的显式降级，
     * 而不是静默放行。</p>
     *
     * <p><b>注意</b>：Redis 在本项目已是强依赖（限流、时间线、计数器），
     * 因此默认 fail-closed 不会显著放大故障面。</p>
     */
    private boolean tokenBlacklistFailOpenOnError = false;

    public boolean isTokenBlacklistFailOpenOnError() {
        return tokenBlacklistFailOpenOnError;
    }

    public void setTokenBlacklistFailOpenOnError(boolean tokenBlacklistFailOpenOnError) {
        this.tokenBlacklistFailOpenOnError = tokenBlacklistFailOpenOnError;
    }
}
