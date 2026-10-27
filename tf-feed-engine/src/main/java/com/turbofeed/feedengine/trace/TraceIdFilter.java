package com.turbofeed.feedengine.trace;

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
 * 入站 traceId 透传（M4 可观测性）：采信网关传来的 {@code X-Trace-Id}，放进 MDC。
 *
 * <p><b>引擎侧是「接棒」而不是「发枪」</b>：正常情况下 id 由网关生成并透传，引擎沿用同一个 id，
 * 于是「网关收到请求 → 调引擎 → 引擎回写 Redis」这几跳的日志能用一条 grep 串起来。
 * 只有直连引擎（压测、内部脚本、网关漏传）时才自己生成——绝不复用上游 id 的前提是它先过了白名单。</p>
 *
 * <h3>为什么这段代码在网关和引擎各有一份</h3>
 * <p>共享的唯一途径是 tf-shared，而 tf-shared 的架构规则禁止任何非 JDK 依赖
 * （{@code org.slf4j} / {@code jakarta.servlet} 都算）。为一段四十行的管道代码破例，
 * 代价是框架耦合扩散到每个服务。因此纯逻辑（生成/校验/常量）共享在
 * {@link TraceIds}，servlet 管道各自一份；两份的契约锚点是 {@code TraceIdsTest}
 * 锁死的 header 名与 MDC 键——<b>改动其一时它会红</b>。</p>
 *
 * <p><b>已知边界</b>：本过滤器只覆盖 HTTP 入站。<b>MQ 消费链路拿不到生产者的 traceId</b>
 * （{@code FeedTimelineEvent} 契约里没有该字段，加字段等于升 schemaVersion），
 * 消费侧会用新生成的 id。这是如实登记的缺口（见 {@code docs/architecture/failure-mode-ledger.md}）。</p>
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
            // 必须清理：线程复用下残留值会把下一笔请求的日志错误地串到这一条上。
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
