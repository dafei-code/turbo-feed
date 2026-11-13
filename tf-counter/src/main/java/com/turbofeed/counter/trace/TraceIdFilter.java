package com.turbofeed.counter.trace;

import com.turbofeed.shared.trace.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * tf-counter 入站 traceId 透传（M4）：覆盖 MQ 关闭时网关 HTTP 兜底
 * {@code POST /internal/counter/incr} 这条路径。
 *
 * <p>与 {@code com.turbofeed.gateway.trace.TraceIdFilter} 同构：入站值先过
 * {@link TraceIds#sanitize} 白名单（防日志注入 / 无界长度），回写响应头，
 * 最高优先级 + {@code finally} 清理（Tomcat 线程复用会把上一笔请求的 id 带进下一笔）。</p>
 *
 * <p><b>为什么值得第三份副本</b>：本服务九成流量确实走 MQ（由 {@code TraceContext} 恢复 id），
 * 但 HTTP 兜底路径一旦被启用，没有 Filter 就会在网关已带上 trace 头的情况下仍生成新 id，
 * 造成「同一笔点赞，网关一个 id、计数服务另一个 id」。补齐后两条路径口径一致。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIds.sanitize(request.getHeader(TraceIds.HEADER));
        if (traceId == null) {
            traceId = TraceIds.generate();
        }
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
