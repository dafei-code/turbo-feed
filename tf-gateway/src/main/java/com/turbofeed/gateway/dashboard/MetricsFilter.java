package com.turbofeed.gateway.dashboard;

import com.turbofeed.gateway.security.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 网关流量采集过滤器（进程内，零外部依赖）。
 *
 * <p>对每个经网关的 API 请求按「读/写」分类计数，并记录写请求耗时与登录用户（近似 DAU），
 * 交给 {@link MetricsCollector} 维护近 60 分钟滚动窗口。管理后台「Feed 流量趋势」面板的数据源。</p>
 *
 * <p><b>分类口径</b>：GET = 读；POST/PUT/DELETE = 写。这把审核、点赞、发布等状态变更归入写，
 * 把推荐流、时间线、搜索、收件箱读取归入读——与管理后台语义一致。</p>
 *
 * <p><b>排除项</b>：管理后台自身轮询（{@code /admin/dashboard}）、静态资源（.html/.js/.css）、
 * actuator 等不计入，避免污染流量曲线。异常请求也照常计入（能反映错误流量）。</p>
 *
 * <p>fail-open：本过滤器只做只读计数，任何异常都不影响主链路（捕获后继续放行）。</p>
 */
@Component
public class MetricsFilter extends OncePerRequestFilter {

    private final MetricsCollector collector;
    private final JwtUtil jwtUtil;

    @Autowired
    public MetricsFilter(MetricsCollector collector, JwtUtil jwtUtil) {
        this.collector = collector;
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (exclude(uri)) {
            filterChain.doFilter(request, response);
            return;
        }
        long startNanos = System.nanoTime();
        boolean write = !"GET".equalsIgnoreCase(request.getMethod());
        String userId = extractUserId(request);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            long now = System.currentTimeMillis();
            try {
                if (write) {
                    collector.recordWrite(now, latencyMs, userId);
                } else {
                    collector.recordRead(now, userId);
                }
            } catch (Exception ignored) {
                // 计数失败绝不影响主链路
            }
        }
    }

    private static boolean exclude(String uri) {
        if (uri == null) {
            return true;
        }
        return uri.contains("/admin/dashboard")
                || uri.endsWith(".html") || uri.endsWith(".js") || uri.endsWith(".css")
                || uri.endsWith(".map") || uri.endsWith(".ico")
                || uri.contains("/static/") || uri.contains("/actuator/");
    }

    /** 从 Bearer 令牌解出 userId（解析失败返回 null，不影响计数）。 */
    private String extractUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        try {
            return jwtUtil.parseClaims(header.substring(7).trim()).userId();
        } catch (Exception e) {
            return null;
        }
    }
}
