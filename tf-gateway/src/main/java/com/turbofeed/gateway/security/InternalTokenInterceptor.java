package com.turbofeed.gateway.security;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 服务间内部接口鉴权拦截器（网关侧，守护 {@code /internal/**}，当前为 {@code /internal/review}）。
 *
 * <p><b>背景</b>：{@code /internal/review/pool-promoted} 仅由 tf-feed-engine 经
 * {@code GatewayReviewClient} 调用（流量池晋级回调），原本不受任何鉴权（评审 doc P0-2 同名项）。
 * 引擎侧同样补齐了 {@code /internal/feed/**} 的令牌校验，本拦截器与之一致：调用方须携带
 * {@code X-Internal-Token}（配置注入 {@code turbofeed.internal.token}），否则 401。</p>
 *
 * <p><b>鉴权策略（fail-fast 优先）</b>：{@code turbofeed.internal.token} 未配置（为空）时，
 * <b>只有 {@code dev} / {@code local} 两个 profile 允许 fail-open</b>（保证「克隆即跑」不受损），
 * 并且启动时打印 banner 大字告警；<b>其余任何 profile（含无 profile 的 default、staging、uat、prod）
 * 一律启动抛 {@link IllegalStateException}</b>，而不是运行期 warn + 放行。
 * 与 {@code JwtProperties}/{@code MinioStorageClient} 凭据校验口径一致，杜绝生产内部接口裸奔。</p>
 *
 * <p><b>为什么是"非 dev/local 即失败"而不是"仅 prod 失败"</b>：内部接口裸奔的判定成本在运行期几乎为零
 * （日志看不到、监控不报警），一旦某个中间环境漏配就永久静默。把 fail-open 收敛成显式白名单，
 * 漏配立刻变成"起不来"，运维可感知、可修复。</p>
 *
 * <p><b>返回形态</b>：抛 {@link BizException}(UNAUTHORIZED) 由 {@code GlobalExceptionHandler}
 * 翻译为 {@code Result}（40101），与业务接口错误体一致。</p>
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    /** 服务间共享令牌头名（引擎侧同名常量保持一致）。 */
    public static final String HEADER = "X-Internal-Token";

    /**
     * 允许 fail-open（缺令牌放行）的 profile 名单：仅 dev / local。
     * 其余 profile（含无 profile 的 default）缺令牌一律启动失败。
     */
    static final Profiles FAIL_OPEN_PROFILES = Profiles.of("dev", "local");

    private static final Logger log = LoggerFactory.getLogger(InternalTokenInterceptor.class);

    private final String expectedToken;

    public InternalTokenInterceptor(@Value("${turbofeed.internal.token:}") String expectedToken,
                                   Environment env) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        if (this.expectedToken.isEmpty()) {
            if (!env.acceptsProfiles(FAIL_OPEN_PROFILES)) {
                // fail-fast：非 dev/local 缺令牌直接启动失败，杜绝内部接口裸奔
                // （与 JwtProperties/MinioStorageClient 凭据校验口径一致）
                throw new IllegalStateException("turbofeed.internal.token 未配置：只有 dev/local profile 允许 /internal/** 无鉴权放行，"
                        + "当前 profile 不在豁免名单内，启动失败。请通过环境变量 TURBOFEED_INTERNAL_TOKEN 注入服务间共享令牌，"
                        + "或以 --spring.profiles.active=dev|local 显式声明本地开发模式。");
            }
            warnFailOpen();
        }
    }

    /**
     * fail-open 启动告警：用 banner 大字块输出，避免被滚动日志淹没。
     * 能走到这里说明是 dev/local 本地模式，属预期行为，但仍需一眼可见。
     */
    private static void warnFailOpen() {
        log.warn("================================================================================");
        log.warn("  安全告警：turbofeed.internal.token 未配置，/internal/** 接口处于【无鉴权 fail-open】状态");
        log.warn("  当前为 dev/local 本地模式（豁免放行，保证克隆即跑）；任何能访问本服务端口的调用方均可调用内部接口。");
        log.warn("  生产部署必须通过环境变量 TURBOFEED_INTERNAL_TOKEN 注入共享令牌（网关与引擎两端同值）。");
        log.warn("================================================================================");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 未配置令牌：仅 dev/local 本地模式可走到这里（构造期已对其它 profile 启动失败），放行。
        if (expectedToken.isEmpty()) {
            return true;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !provided.equals(expectedToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部接口需要有效的 X-Internal-Token");
        }
        return true;
    }
}
