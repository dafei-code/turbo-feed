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
 * <p><b>鉴权策略</b>：{@code turbofeed.internal.token} 未配置（为空）时，非 {@code prod} 环境跳过校验
 * （fail-open，保证「克隆即跑」）；<b>prod 环境则启动抛 {@link IllegalStateException}（fail-closed）</b>，
 * 与 {@code JwtProperties}/{@code MinioStorageClient} 凭据校验口径一致，杜绝生产内部接口裸奔。</p>
 *
 * <p><b>返回形态</b>：抛 {@link BizException}(UNAUTHORIZED) 由 {@code GlobalExceptionHandler}
 * 翻译为 {@code Result}（40101），与业务接口错误体一致。</p>
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    /** 服务间共享令牌头名（引擎侧同名常量保持一致）。 */
    public static final String HEADER = "X-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(InternalTokenInterceptor.class);

    private final String expectedToken;
    private final boolean prodProfile;

    public InternalTokenInterceptor(@Value("${turbofeed.internal.token:}") String expectedToken,
                                   Environment env) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.prodProfile = env.acceptsProfiles(Profiles.of("prod"));
        if (this.expectedToken.isEmpty()) {
            if (prodProfile) {
                // fail-closed：生产缺令牌直接启动失败，杜绝内部接口裸奔（与 JwtProperties/Minio 凭据校验口径一致）
                throw new IllegalStateException("turbofeed.internal.token 未配置：prod 环境下 /internal/** 接口不能无鉴权放行，"
                        + "启动失败。请通过环境变量 TURBOFEED_INTERNAL_TOKEN 注入服务间共享令牌。");
            }
            log.warn("turbofeed.internal.token 未配置：/internal/** 接口处于无鉴权（fail-open）状态，"
                    + "生产部署务必配置 X-Internal-Token 共享密钥");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 未配置令牌：开发/克隆即跑模式，跳过校验（fail-open）。
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
