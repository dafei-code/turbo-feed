package com.turbofeed.feedengine.trace;

import com.turbofeed.shared.trace.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M4：引擎 → 网关回流这一跳的 traceId 出站透传。
 *
 * <p>改造前引擎的两个 {@code RestClient.Builder} 都没有拦截器，
 * {@code GatewayReviewClient} 的加严回调在网关侧会是一条全新的链，且双方都不报错。
 * 这里直接断言请求头，避免「看起来做了实际没做」。</p>
 */
class TracePropagationInterceptorTest {

    private final TracePropagationInterceptor interceptor = new TracePropagationInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private MockClientHttpRequest execute() throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("http://gateway/internal/review/pool-promoted"));
        interceptor.intercept(request, new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));
        return request;
    }

    @Test
    @DisplayName("消费线程恢复了 traceId：回流请求必须带上它")
    void withTraceId_setsHeader() throws Exception {
        MDC.put(TraceIds.MDC_KEY, "probe-abc123");
        assertEquals("probe-abc123", execute().getHeaders().getFirst(TraceIds.HEADER));
    }

    @Test
    @DisplayName("无 traceId（定时任务/启动期）：不加空头")
    void withoutTraceId_noHeader() throws Exception {
        assertNull(execute().getHeaders().getFirst(TraceIds.HEADER));
    }

    @Test
    @DisplayName("已有同名头时覆盖而不是追加")
    void existingHeader_overwritten() throws Exception {
        MDC.put(TraceIds.MDC_KEY, "new-id");
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET, URI.create("http://gateway/x"));
        request.getHeaders().add(TraceIds.HEADER, "stale-id");

        interceptor.intercept(request, new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        assertEquals(1, request.getHeaders().get(TraceIds.HEADER).size());
        assertEquals("new-id", request.getHeaders().getFirst(TraceIds.HEADER));
    }
}
