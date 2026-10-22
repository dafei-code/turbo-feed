package com.turbofeed.feedengine.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 服务间内部接口鉴权拦截器（引擎侧，守护 {@code /internal/**}）。
 *
 * <p><b>背景</b>：{@code /internal/feed/**} 仅由 tf-gateway 经 {@code FeedEngineClient} 调用，
 * 原本依赖"内网可信"、无任何鉴权（评审 doc P0-2 同名项）。本拦截器补齐服务间令牌校验：
 * 调用方须携带与引擎一致的 {@code X-Internal-Token}（配置注入 {@code turbofeed.internal.token}），
 * 否则返回 401。</p>
 *
 * <p><b>鉴权策略</b>：{@code turbofeed.internal.token} 未配置（为空）时，非 {@code prod} 环境跳过校验
 * （fail-open，保证「克隆即跑」）；<b>prod 环境则启动抛 {@link IllegalStateException}（fail-closed）</b>，
 * 与网关侧口径一致，杜绝生产内部接口裸奔。</p>
 *
 * <p><b>为什么不用 Spring Security</b>：与网关一致，项目未引入 Security；拦截器在 DispatcherServlet
 * 内运行，直接写 401 + {@code Result} 形态 JSON（与 {@code FeedTimelineController} 返回体一致）。</p>
 */
@Slf4j
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    /** 服务间共享令牌头名（网关侧同名常量保持一致）。 */
    public static final String HEADER = "X-Internal-Token";

    private final String expectedToken;
    private final boolean prodProfile;

    public InternalTokenInterceptor(@Value("${turbofeed.internal.token:}") String expectedToken,
                                   Environment env) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.prodProfile = env.acceptsProfiles(Profiles.of("prod"));
        if (this.expectedToken.isEmpty()) {
            if (prodProfile) {
                // fail-closed：生产缺令牌直接启动失败，杜绝内部接口裸奔（与网关侧口径一致）
                throw new IllegalStateException("turbofeed.internal.token 未配置：prod 环境下 /internal/** 接口不能无鉴权放行，"
                        + "启动失败。请通过环境变量 TURBOFEED_INTERNAL_TOKEN 注入服务间共享令牌。");
            }
            log.warn("turbofeed.internal.token 未配置：/internal/** 接口处于无鉴权（fail-open）状态，"
                    + "生产部署务必配置 X-Internal-Token 共享密钥，否则内部接口可被任意调用");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 未配置令牌：开发/克隆即跑模式，跳过校验（fail-open）。
        if (expectedToken.isEmpty()) {
            return true;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !provided.equals(expectedToken)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":40101,\"message\":\"内部接口需要有效的 X-Internal-Token\"}");
            return false;
        }
        return true;
    }
}
