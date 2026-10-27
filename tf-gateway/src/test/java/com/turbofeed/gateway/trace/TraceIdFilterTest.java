package com.turbofeed.gateway.trace;

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
 * M4 靶心：入站 traceId 过滤器（{@link TraceIdFilter}）。
 *
 * <p>四条断言分别对应四个真实故障：</p>
 * <ul>
 *   <li>MDC 在链内可见 —— 否则加了过滤器日志里照样没有 id；</li>
 *   <li>响应头回写 —— 否则用户报障时说不出 id，这个功能等于只服务了服务端自己；</li>
 *   <li>非法入站值被替换 —— 否则 {@code \n} 能伪造日志行（注入）；</li>
 *   <li>链结束后 MDC 被清空 —— 否则 Tomcat 线程复用会把上一笔的 id 带进下一笔，
 *       让两笔请求的日志错误地串成一条，比没有 traceId 更难排查。</li>
 * </ul>
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    /** 一次过滤过程中，链内看到的 MDC 值（链结束后会被清，只能就地取）。 */
    private final AtomicReference<String> inChain = new AtomicReference<>();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 跑一次完整过滤，返回响应对象（链内 MDC 值走 {@link #inChain}）。 */
    private MockHttpServletResponse run(String incomingHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/feed/recommended");
        if (incomingHeader != null) {
            request.addHeader(TraceIds.HEADER, incomingHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> inChain.set(MDC.get(TraceIds.MDC_KEY));
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("无入站值：生成 32 位十六进制，链内可见并回写响应头")
    void noIncomingHeader_generates() throws Exception {
        MockHttpServletResponse response = run(null);

        String traceId = inChain.get();
        assertNotNull(traceId, "链内 MDC 必须有值，否则这条请求的日志依然没有 traceId");
        assertTrue(traceId.matches("[0-9a-f]{32}"), "应为 32 位十六进制: " + traceId);
        assertEquals(traceId, response.getHeader(TraceIds.HEADER), "响应头必须回写同一个 id");
    }

    @Test
    @DisplayName("合法入站值：原样沿用（保证跨服务链路能串起来）")
    void validIncomingHeader_reused() throws Exception {
        MockHttpServletResponse response = run("trace-abc_123");

        assertEquals("trace-abc_123", inChain.get());
        assertEquals("trace-abc_123", response.getHeader(TraceIds.HEADER));
    }

    @Test
    @DisplayName("非法入站值（换行注入）：弃用并重新生成，绝不让脏值进 MDC")
    void illegalIncomingHeader_regenerated() throws Exception {
        String poisoned = "abc\n2026-10-27 10:00:00 ERROR 【伪造】审核通过 mediaId=1";
        run(poisoned);

        String traceId = inChain.get();
        assertNotEquals(poisoned, traceId);
        assertFalse(traceId.contains("\n"), "MDC 里出现换行等于允许伪造日志行");
        assertTrue(traceId.matches("[0-9a-f]{32}"));
    }

    @Test
    @DisplayName("链抛异常：MDC 仍被清空（finally 保证）")
    void chainThrows_mdcStillCleared() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("downstream failed");
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, boom));
        assertNull(MDC.get(TraceIds.MDC_KEY),
                "异常路径没清 MDC 的话，线程被复用后上一笔的 id 会污染下一笔");
    }

    @Test
    @DisplayName("链结束后 MDC 清空（线程复用安全）")
    void afterChain_mdcCleared() throws Exception {
        run("abc");
        assertNull(MDC.get(TraceIds.MDC_KEY));
    }
}
