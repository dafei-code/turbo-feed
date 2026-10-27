package com.turbofeed.gateway.trace;

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
 * 入站 traceId 透传（M4 可观测性）：采信上游 {@code X-Trace-Id}，放进 MDC 供整条请求的日志使用。
 *
 * <h3>为什么必须有它</h3>
 * <p>没有 traceId 时，排查「用户说刷不出内容」要在网关、引擎、计数三份日志里按时间戳手工对齐；
 * 高 QPS 下同一毫秒有几十条日志，实际根本对不上。有了它一条 {@code grep} 就能拉出整条链路。</p>
 *
 * <h3>三个设计决定</h3>
 * <ol>
 *   <li><b>不采信上游原值，先过白名单</b>：见 {@link TraceIds#sanitize}。入站 header 不可信，
 *       带 {@code \n} 的值能把伪造的日志行写进文件（日志注入），超长值会撑爆日志与采集链路。
 *       不合规就生成新的——宁可丢上游的关联性，也不让不可信内容进日志。</li>
 *   <li><b>回写响应头</b>：让前端/客户端能把 id 报给客服或贴进工单。
 *       否则这个 id 只存在于服务端日志里，用户侧无从提供。</li>
 *   <li><b>最高优先级 + finally 清理</b>：必须排在所有业务过滤器之前，否则前面的日志没有 id；
 *       清理必须放 finally，否则线程复用（Tomcat 线程池）会把上一笔请求的 id 带进下一笔——
 *       那比没有 traceId 更危险，会把两笔请求的日志错误地串成一条。</li>
 * </ol>
 *
 * <h3>为什么这段代码在网关和引擎各有一份</h3>
 * <p>共享的唯一途径是 tf-shared，而 tf-shared 的架构规则明令禁止任何非 JDK 依赖
 * （{@code org.slf4j} 与 {@code jakarta.servlet} 都算，见 {@code ArchitectureTest}）——
 * 为了一段四十行的管道代码去破例，代价是这份框架耦合会扩散到每个服务。
 * 因此：<b>纯逻辑（生成/校验/常量）共享在 {@link TraceIds}，servlet 管道各自一份</b>。
 * 两份的唯一契约锚点是 {@code TraceIdsTest} 里锁死的 header 名与 MDC 键。</p>
 *
 * <p><b>已知边界</b>：只覆盖 HTTP 入站。MQ 消费侧目前拿不到生产者 traceId
 * （契约里没有该字段，加字段等于升 schemaVersion），消费链路用新生成的 id。</p>
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
            // 必须清理：Tomcat 线程复用，残留的值会把下一笔请求的日志串到这一条上。
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
