package com.turbofeed.feedengine.trace;

import com.turbofeed.shared.trace.TraceIds;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 靶心：引擎侧 traceId 过滤器（{@link TraceIdFilter}）。
 *
 * <p>引擎侧是「接棒」：网关传什么就用什么，这样一次请求在网关与引擎的日志才是同一条链。
 * 因此这里最关键的一条是 <b>合法入站值必须原样沿用</b>——如果引擎自作聪明重新生成，
 * 反倒把链路切断了，而且同样不会有任何报错。</p>
 *
 * <p>细节边界（日志注入、超长、边界长度）由 tf-shared 的 {@code TraceIdsTest} 锁死，
 * 此处不重复；这里只验「过滤器把这套规则用在了错误的位置没有」。</p>
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();
    private final AtomicReference<String> inChain = new AtomicReference<>();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private MockHttpServletResponse run(String incomingHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/feed/timeline/append");
        if (incomingHeader != null) {
            request.addHeader(TraceIds.HEADER, incomingHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> inChain.set(MDC.get(TraceIds.MDC_KEY));
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("沿用网关传入的 id（接棒，不重新生成）")
    void reusesIncomingTraceId() throws Exception {
        MockHttpServletResponse response = run("gw-trace-001");

        assertEquals("gw-trace-001", inChain.get(), "重新生成会把网关与引擎的日志切成两条链");
        assertEquals("gw-trace-001", response.getHeader(TraceIds.HEADER));
    }

    @Test
    @DisplayName("无入站值（直连引擎/压测/网关漏传）：自己生成")
    void generatesWhenAbsent() throws Exception {
        run(null);

        assertNotNull(inChain.get());
        assertTrue(inChain.get().matches("[0-9a-f]{32}"), "应为 32 位十六进制: " + inChain.get());
    }

    @Test
    @DisplayName("非法入站值：弃用并重新生成")
    void rejectsIllegalIncoming() throws Exception {
        run("abc\ndef");

        assertFalse(inChain.get().contains("\n"), "换行进 MDC 等于允许伪造日志行");
        assertTrue(inChain.get().matches("[0-9a-f]{32}"));
    }

    @Test
    @DisplayName("链结束后 MDC 清空（线程复用安全）")
    void clearsMdcAfterChain() throws Exception {
        run("gw-trace-002");
        assertNull(MDC.get(TraceIds.MDC_KEY));
    }
}
