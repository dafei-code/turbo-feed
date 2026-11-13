package com.turbofeed.gateway.trace;

import com.turbofeed.shared.trace.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4：MQ 消费侧 / 异步线程池的 MDC 管道契约。
 *
 * <p>最要紧的是「线程复用」这条：只 put 不清会把上一批消息的 id 带进下一批，
 * 把两笔请求错误地串成一条；只 clear 又会抹掉外层 TaskDecorator 传进来的上下文。
 * 所以这里同时断言<b>沿用上游值</b>与<b>还原 previous 快照</b>。</p>
 */
class TraceContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("current：非请求线程返回 null，有值时原样返回")
    void current_readsMdc() {
        assertNull(TraceContext.current());
        MDC.put(TraceIds.MDC_KEY, "probe-abc123");
        assertEquals("probe-abc123", TraceContext.current());
        MDC.put(TraceIds.MDC_KEY, "   ");
        assertNull(TraceContext.current(), "空白值等同于没有，不该发进消息体");
    }

    @Test
    @DisplayName("begin：沿用生产者消息体里的 id，消费日志与生产日志同链")
    void begin_reusesProducerTraceId() {
        Map<String, String> previous = TraceContext.begin("probe-abc123");
        try {
            assertEquals("probe-abc123", MDC.get(TraceIds.MDC_KEY));
        } finally {
            TraceContext.end(previous);
        }
    }

    @Test
    @DisplayName("begin：缺失或伪造值回退为本地新 id，绝不把换行等不可信内容放进 MDC")
    void begin_generatesOnMissingOrUnsafeValue() {
        for (String bad : new String[]{null, "abc\nINFO 伪造日志行", "a".repeat(65)}) {
            Map<String, String> previous = TraceContext.begin(bad);
            try {
                String actual = MDC.get(TraceIds.MDC_KEY);
                assertTrue(actual.matches("[0-9a-f]{32}"), "应生成新的合规 id: " + bad);
            } finally {
                TraceContext.end(previous);
            }
        }
    }

    @Test
    @DisplayName("end：恢复调用前完整上下文，而不是简单 clear")
    void end_restoresPreviousContext() {
        MDC.put(TraceIds.MDC_KEY, "outer-id");
        MDC.put("anotherKey", "keep-me");

        Map<String, String> previous = TraceContext.begin("inner-id");
        assertEquals("inner-id", MDC.get(TraceIds.MDC_KEY));
        TraceContext.end(previous);

        assertEquals("outer-id", MDC.get(TraceIds.MDC_KEY), "外层上下文被抹掉会断掉嵌套调用链");
        assertEquals("keep-me", MDC.get("anotherKey"));
    }

    @Test
    @DisplayName("end：调用前无上下文时清干净，防止消费线程把 id 带进下一批消息")
    void end_clearsWhenNoPreviousContext() {
        Map<String, String> previous = TraceContext.begin("batch-1");
        TraceContext.end(previous);
        assertNull(MDC.get(TraceIds.MDC_KEY));

        Map<String, String> second = TraceContext.begin(null);
        String generated = MDC.get(TraceIds.MDC_KEY);
        TraceContext.end(second);
        assertNotEquals("batch-1", generated, "上一批的 id 绝不能残留到下一批");
    }
}
