package com.turbofeed.gateway.trace;

import com.turbofeed.shared.trace.TraceIds;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 出站请求带上当前 traceId（M4）：让「网关 → 引擎」这一跳的日志与入站请求串成一条。
 *
 * <p><b>为什么单独成类而不是在配置里写个 lambda</b>：lambda 藏在一个 {@code @Bean} 方法里，
 * 想说清「它到底加没加头、加了哪个头」就只能启动整个 Spring 上下文做端到端断言；
 * 抽成类之后可以用 {@code MockClientHttpRequest} 直接验，
 * 测试成本从「起容器」降到「调一个方法」。可观测性代码本身如果没法被测，就没人敢改。</p>
 *
 * <p><b>为什么没有 traceId 时不加空头</b>：定时任务、启动期调用不在 HTTP 请求线程上，
 * MDC 里没有值。此时加一个 {@code X-Trace-Id: } 空头会让下游把空串当作合法 ID
 * （{@code TraceIds.sanitize} 会拒绝空串并重新生成，但白白多了一次生成 + 一次响应头回写）。
 * 不发头比发空头更干净。</p>
 */
public class TracePropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = MDC.get(TraceIds.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(TraceIds.HEADER, traceId);
        }
        return execution.execute(request, body);
    }
}
