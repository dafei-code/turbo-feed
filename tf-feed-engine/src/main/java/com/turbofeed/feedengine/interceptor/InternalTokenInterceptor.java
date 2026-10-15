package com.turbofeed.feedengine.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * <p><b>fail-open 开发模式</b>：当 {@code turbofeed.internal.token} 未配置（为空）时跳过校验，
 * 保证「克隆即跑」本地开发/测试不被拦。生产部署<b>必须</b>配置该令牌，否则内部接口等同于裸奔——
 * 未配置时会打 WARN 提醒（与网关侧同口径）。</p>
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

    public InternalTokenInterceptor(@Value("${turbofeed.internal.token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        if (this.expectedToken.isEmpty()) {
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
