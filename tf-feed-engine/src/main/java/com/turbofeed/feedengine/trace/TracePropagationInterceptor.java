package com.turbofeed.feedengine.trace;

import com.turbofeed.shared.trace.TraceIds;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 引擎出站请求带上当前 traceId（M4）：让「引擎 → 网关」的回流日志与来源请求串成一条。
 *
 * <p>网关侧已有同名实现（{@code com.turbofeed.gateway.trace.TracePropagationInterceptor}）。
 * 这里不抽到 tf-shared：该模块禁止 slf4j / spring 依赖，与两份 {@code TraceIdFilter} 的取舍一致。</p>
 *
 * <p><b>为什么必须补这一份</b>：引擎的 {@code GatewayReviewClient} 用本服务的两个
 * {@code RestClient.Builder} 发请求（池级晋级加严回调）。改造前这两个 builder 没挂拦截器，
 * 于是「用户上传 → MQ → 引擎审核 → 回流网关」这条链路在最后一跳静默断开：
 * 网关日志里有 id、引擎日志里也有 id，但引擎发出的那个请求在网关侧是一条全新的 id。</p>
 *
 * <p><b>为什么没有 traceId 时不加空头</b>：定时任务、启动期调用不在请求线程上，MDC 里没有值。
 * 发一个空 {@code X-Trace-Id} 会让下游白白多一次生成与响应头回写；不发头更干净。</p>
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
