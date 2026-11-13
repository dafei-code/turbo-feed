package com.turbofeed.gateway.trace;

import com.turbofeed.shared.trace.InstanceIds;
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
import java.util.Map;

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
 *   <li><b>回写响应头</b>：让前端/客户端能把 id 报给客服或贴进工单。</li>
 *   <li><b>最高优先级 + finally 恢复完整快照</b>：必须排在所有业务过滤器之前；清理时必须
 *       恢复调用前的<b>整份</b> MDC 快照，而不是只 {@code remove(traceId)}——后者会让业务代码
 *       后来放进 MDC 的 {@code userId} / {@code postId} / 错误指纹等键残留到下一笔请求，
 *       在 Tomcat 线程复用下造成串号甚至 PII 泄漏。完整快照恢复同时兼容嵌套调用与
 *       {@code CallerRunsPolicy} 让任务退回调用线程同步执行的场景。</li>
 * </ol>
 *
 * <h3>为什么这段代码在网关和引擎各有一份</h3>
 * <p>共享的唯一途径是 tf-shared，而 tf-shared 的架构规则明令禁止任何非 JDK 依赖
 * （{@code org.slf4j} 与 {@code jakarta.servlet} 都算，见 {@code ArchitectureTest}）——
 * 为了一段四十行的管道代码去破例，代价是这份框架耦合会扩散到每个服务。
 * 因此：<b>纯逻辑（生成/校验/常量）共享在 {@link TraceIds}，servlet 管道各自一份</b>。
 * 两份的唯一契约锚点是 {@code TraceIdsTest} 里锁死的 header 名与 MDC 键。</p>
 *
 * <p><b>边界</b>：本过滤器只覆盖 HTTP 入站。MQ 消费侧由 {@link TraceContext} 从消息体的
 * 可空 {@code traceId} 字段恢复 MDC；定时调度由 {@code TraceContext.beginCycle()} 建立轮次上下文；
 * 异步线程池由 {@code AsyncConfig} 的 {@code TaskDecorator} 传递。
 * 所有入口都会注入 {@code instanceId}，多副本集中日志里才能区分来源实例。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        String traceId = TraceIds.sanitize(request.getHeader(TraceIds.HEADER));
        if (traceId == null) {
            traceId = TraceIds.generate();
        }
        MDC.put(TraceIds.MDC_KEY, traceId);
        MDC.put(TraceIds.MDC_INSTANCE_KEY, InstanceIds.current());
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 恢复完整快照：只 remove traceId 会让业务新增的 MDC 键随线程复用串到下一笔请求。
            TraceContext.end(previous);
        }
    }
}
