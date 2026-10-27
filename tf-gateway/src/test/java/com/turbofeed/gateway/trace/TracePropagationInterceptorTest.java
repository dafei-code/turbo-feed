package com.turbofeed.gateway.trace;

import com.turbofeed.shared.trace.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 靶心：出站 traceId 透传（{@link TracePropagationInterceptor}）。
 *
 * <p>这一跳最容易「看起来做了实际没做」：网关注入 MDC、日志里也有 id，
 * 但请求头没带，引擎侧的日志就是另一条孤立的链——两边都不报错。
 * 所以这里直接断言<b>请求头</b>上有没有这个值。</p>
 */
class TracePropagationInterceptorTest {

    private final TracePropagationInterceptor interceptor = new TracePropagationInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private MockClientHttpRequest execute() throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://engine/internal/feed/timeline/append"));
        interceptor.intercept(request, new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));
        return request;
    }

    @Test
    @DisplayName("有 traceId：写进出站请求头")
    void withTraceId_setsHeader() throws Exception {
        MDC.put(TraceIds.MDC_KEY, "trace-abc_123");

        assertEquals("trace-abc_123", execute().getHeaders().getFirst(TraceIds.HEADER),
                "没带这个头，网关与引擎的日志就是两条互不相干的链，且不会报任何错");
    }

    @Test
    @DisplayName("无 traceId（定时任务/启动期）：不加空头")
    void withoutTraceId_noHeader() throws Exception {
        // 空串头会让下游误以为「上游传了 id」，白白多一次 sanitize 失败 + 重新生成。
        assertNull(execute().getHeaders().getFirst(TraceIds.HEADER));
    }

    @Test
    @DisplayName("空白值同样视为没有：不发一个只有空格的头")
    void blankTraceId_noHeader() throws Exception {
        MDC.put(TraceIds.MDC_KEY, "   ");

        assertNull(execute().getHeaders().getFirst(TraceIds.HEADER));
    }

    @Test
    @DisplayName("已有同名头时覆盖，而不是追加出两个值")
    void existingHeader_overwritten() throws Exception {
        MDC.put(TraceIds.MDC_KEY, "new-id");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://engine/x"));
        request.getHeaders().add(TraceIds.HEADER, "stale-id");

        interceptor.intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        // set 而非 add：两个值会让下游取到哪一个变成实现细节，排查时看到的是错的那条。
        assertEquals(1, request.getHeaders().get(TraceIds.HEADER).size());
        assertEquals("new-id", request.getHeaders().getFirst(TraceIds.HEADER));
    }
}
