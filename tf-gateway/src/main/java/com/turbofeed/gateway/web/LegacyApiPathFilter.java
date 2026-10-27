package com.turbofeed.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 老接口路径兼容层：把 {@code /api/**} 重写到 {@code /api/v1/**}（P2-3）。
 *
 * <p><b>为什么要重写而不是让两套路径并存</b>：并存意味着每个控制器写两个映射、每个新接口
 * 都要记得补两份——漏一份的后果是「新接口在老路径下 404」，而且没有任何测试会红。
 * 重写只在一处发生：控制器<b>只声明版本化路径</b>，老路径由本过滤器改写到同一处理器。</p>
 *
 * <p><b>重写发生在哪里</b>：Spring MVC 的过滤器链里、{@code DispatcherServlet} 之前，
 * 因此后续的 CORS 匹配、{@code HandlerMapping} 路由、拦截器（如 RBAC 的
 * {@code PermissionInterceptor}）看到的都已经是版本化路径。这带来一个<b>容易漏的连带改动</b>：
 * 拦截器注册的 Ant 模式如果写死 {@code /api/admin/**}，就匹配不到 {@code /api/v1/admin/**}——
 * 后台接口会静默失去鉴权。{@code WebConfig} 因此同时注册了新旧两个模式。</p>
 *
 * <p><b>弃用信号</b>：响应带两个标准头（不改变响应体，老客户端无感）：</p>
 * <ul>
 *   <li>{@code Deprecation: @<epoch 秒>}——RFC 9745，值为「开始被视为弃用的时刻」，不是移除时刻；</li>
 *   <li>{@code Link: </api/v1/xxx>; rel="successor-version"}——告诉客户端该换成哪个 URL。</li>
 * </ul>
 * <p>刻意<b>不发</b> {@code Sunset} 头：本仓库没有承诺移除日期，发一个假日期比不发更糟
 * （客户端会据此做迁移排期，然后被打脸）。真要移除时再补 {@code Sunset}。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LegacyApiPathFilter extends OncePerRequestFilter {

    /** 响应头名（常量偏公开：测试与文档都要引用同一个字面量，避免两处各写一遍）。 */
    public static final String DEPRECATION_HEADER = "Deprecation";
    public static final String LINK_HEADER = "Link";

    /**
     * 弃用生效时刻，RFC 9745 要求的 {@code @<epoch 秒>} 形态。
     *
     * <p>取「本特性上线日」而不是构建时间：它描述的是<b>约定何时改变</b>，与部署节奏无关，
     * 每次发版都变的话客户端无法据此判断自己落后多久。</p>
     */
    private static final String DEPRECATED_AT =
            "@" + Instant.parse("2026-10-27T00:00:00Z").getEpochSecond();

    /**
     * 已是版本化路径的形态 {@code /api/v{N}...}。
     *
     * <p>必须显式识别，否则 {@code /api/v1/feed} 会被再改写成 {@code /api/v1/v1/feed}——
     * 表现为「新路径 404、老路径正常」，而重写逻辑本身看起来完全正确。</p>
     */
    private static final Pattern ALREADY_VERSIONED =
            Pattern.compile("^" + Pattern.quote(ApiVersion.LEGACY) + "/v\\d+(/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String target = versionedPathOf(request.getRequestURI());
        if (target == null) {
            filterChain.doFilter(request, response);
            return;
        }
        // 头写在 chain 之前：响应体一旦开始写出，头就无法再改
        response.setHeader(DEPRECATION_HEADER, DEPRECATED_AT);
        response.setHeader(LINK_HEADER, "<" + target + ">; rel=\"successor-version\"");
        filterChain.doFilter(new VersionedRequest(request, target), response);
    }

    /**
     * 计算应当改写到的版本化路径。
     *
     * @return 目标路径；{@code null} 表示<b>不需要改写</b>（不是 {@code /api/**}、或已是版本化路径）
     */
    static String versionedPathOf(String requestUri) {
        if (requestUri == null || !requestUri.startsWith(ApiVersion.LEGACY + "/")) {
            return null;
        }
        if (ALREADY_VERSIONED.matcher(requestUri).matches()) {
            return null;
        }
        return ApiVersion.V1 + requestUri.substring(ApiVersion.LEGACY.length());
    }

    /**
     * 把 URI / URL / servletPath 一并替换成版本化路径的请求包装。
     *
     * <p><b>为什么三个都要改</b>：Spring MVC 用 {@code getRequestURI()} 做路径匹配（Boot 3 的
     * {@code PathPatternParser}），而 {@code getRequestURL()} 是日志 / 监控 / 审计里最常被引用的那个；
     * 只改其一会出现「路由对了但访问日志里记的是老路径」，排障时对不上账。
     * {@code servletPath} 与 {@code pathInfo} 跟随 {@code DispatcherServlet} 挂在 {@code "/"} 的语义。</p>
     */
    private static final class VersionedRequest extends HttpServletRequestWrapper {

        private final String uri;
        private final String url;

        VersionedRequest(HttpServletRequest request, String uri) {
            super(request);
            this.uri = uri;
            // 原始 URL 是 scheme://host:port + 原始 URI，按长度差取出协议与主机部分，避免手工拼端口
            StringBuffer original = request.getRequestURL();
            String originalUri = request.getRequestURI();
            String origin = original.substring(0, original.length() - originalUri.length());
            this.url = origin + uri;
        }

        @Override
        public String getRequestURI() {
            return uri;
        }

        @Override
        public StringBuffer getRequestURL() {
            return new StringBuffer(url);
        }

        @Override
        public String getServletPath() {
            return uri;
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
