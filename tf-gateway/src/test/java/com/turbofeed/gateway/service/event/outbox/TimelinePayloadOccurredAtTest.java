package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.gateway.service.feed.FeedTimelinePublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-D3：时间线事件的 {@code occurredAt} 必须在 enqueue 时刻固化，并在重投时原样透传。
 *
 * <p>这里锁住的正是评审验收场景的前置条件：DEAD 行被 Sweeper 重投时，Handler 从库里读
 * payload JSON 重建事件。若在这里用 {@code Instant.now()}，迟到 APPEND 的时间戳会比同一帖
 * 后续 REMOVE 更新，引擎侧墓碑比对必然漏判、已下架内容复活。<b>这类 bug 平时测试全绿，
 * 只在 MQ / 引擎抖动并发生重投后暴露</b>，故必须用「固定时刻 + 存量兼容」两组断言钉死。</p>
 */
class TimelinePayloadOccurredAtTest {

    private static final Instant ENQUEUE_AT = Instant.parse("2026-11-16T08:00:00Z");
    private static final Instant LATER_REMOVE_AT = Instant.parse("2026-11-16T09:30:00Z");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ==================== payload 往返与存量兼容 ====================

    @Test
    void payload_roundTrip_keepsEnqueueMomentAndTraceId() throws Exception {
        String appendJson = mapper.writeValueAsString(
                new TimelineAppendPayload(null, 3, ENQUEUE_AT, "trace-a"));
        TimelineAppendPayload append = mapper.readValue(appendJson, TimelineAppendPayload.class);
        assertEquals(ENQUEUE_AT, append.occurredAt());
        assertEquals("trace-a", append.traceId());
        assertEquals(3, append.poolLevel());

        String removeJson = mapper.writeValueAsString(
                new TimelineRemovePayload("post/1/x", LATER_REMOVE_AT, "trace-r"));
        TimelineRemovePayload remove = mapper.readValue(removeJson, TimelineRemovePayload.class);
        assertEquals(LATER_REMOVE_AT, remove.occurredAt());
        assertEquals("trace-r", remove.traceId());
        assertEquals("post/1/x", remove.timelineKey());
    }

    @Test
    void legacyPayload_withoutOccurredAt_deserializesNull_andWillBeTolerated() throws Exception {
        // 存量事件行只有 item/poolLevel/traceId 或 timelineKey/traceId
        TimelineAppendPayload append = mapper.readValue(
                "{\"item\":null,\"poolLevel\":2,\"traceId\":\"trace-old\"}", TimelineAppendPayload.class);
        assertNull(append.occurredAt());
        assertEquals("trace-old", append.traceId());

        TimelineRemovePayload remove = mapper.readValue(
                "{\"timelineKey\":\"post/1/x\",\"traceId\":\"trace-old\"}", TimelineRemovePayload.class);
        assertNull(remove.occurredAt());
        assertEquals("trace-old", remove.traceId());
    }

    @Test
    void legacyConstructors_stillProduceNullOccurredAt() {
        // PR-D3 之前的生产形态与 2 参存量形态，都必须按「时刻未知」放行
        assertNull(new TimelineAppendPayload(null, 1).occurredAt());
        assertNull(new TimelineAppendPayload(null, 1, "t").occurredAt());
        assertNull(new TimelineRemovePayload("p1").occurredAt());
        assertNull(new TimelineRemovePayload("p1", "t").occurredAt());
    }

    // ==================== Handler 透传：重投绝不刷新时刻 ====================

    @Test
    void appendHandler_replaysOriginalEnqueueMoment_notNow() throws Exception {
        FeedTimelinePublisher publisher = mock(FeedTimelinePublisher.class);
        when(publisher.append(any(), anyInt(), any())).thenReturn(true);
        TimelineAppendHandler handler = new TimelineAppendHandler(mapper, publisher);

        // 模拟「几小时后从库里读出 DEAD 行重投」：JSON 里的时刻仍是 enqueue 时刻
        String storedJson = mapper.writeValueAsString(
                new TimelineAppendPayload(null, 2, ENQUEUE_AT, "trace-a"));
        assertDoesNotThrow(() -> handler.handle(storedJson));

        // 关键断言：透传的必须是 enqueue 时刻，不能是重投这一跳的 Instant.now()
        verify(publisher).append(isNull(), eq(2), eq(ENQUEUE_AT));
    }

    @Test
    void removeHandler_replaysOriginalEnqueueMoment() throws Exception {
        FeedTimelinePublisher publisher = mock(FeedTimelinePublisher.class);
        when(publisher.remove(any(), any())).thenReturn(true);
        TimelineRemoveHandler handler = new TimelineRemoveHandler(mapper, publisher);

        String storedJson = mapper.writeValueAsString(
                new TimelineRemovePayload("post/1/x", LATER_REMOVE_AT, "trace-r"));
        assertDoesNotThrow(() -> handler.handle(storedJson));

        verify(publisher).remove(eq("post/1/x"), eq(LATER_REMOVE_AT));
    }

    @Test
    void legacyReplay_passesNullOccurredAt_soEngineLetsItThrough() throws Exception {
        FeedTimelinePublisher publisher = mock(FeedTimelinePublisher.class);
        when(publisher.append(any(), anyInt(), isNull())).thenReturn(true);
        when(publisher.remove(any(), isNull())).thenReturn(true);

        new TimelineAppendHandler(mapper, publisher)
                .handle("{\"item\":null,\"poolLevel\":1,\"traceId\":\"old\"}");
        new TimelineRemoveHandler(mapper, publisher)
                .handle("{\"timelineKey\":\"post/1/x\",\"traceId\":\"old\"}");

        verify(publisher).append(isNull(), eq(1), isNull());
        verify(publisher).remove(eq("post/1/x"), isNull());
    }

    @Test
    void handler_deliveryFailure_stillThrows_soOutboxRetries() {
        FeedTimelinePublisher publisher = mock(FeedTimelinePublisher.class);
        when(publisher.append(any(), anyInt(), any())).thenReturn(false);

        TimelineAppendHandler append = new TimelineAppendHandler(mapper, publisher);
        assertThrows(Exception.class, () -> append.handle(
                "{\"item\":null,\"poolLevel\":1,\"occurredAt\":null,\"traceId\":null}"));
        verify(publisher).append(any(), anyInt(), isNull());

        FeedTimelinePublisher removePublisher = mock(FeedTimelinePublisher.class);
        when(removePublisher.remove(any(), any())).thenReturn(false);
        TimelineRemoveHandler remove = new TimelineRemoveHandler(mapper, removePublisher);
        assertThrows(Exception.class, () -> remove.handle(
                "{\"timelineKey\":\"post/1/x\",\"occurredAt\":null,\"traceId\":null}"));
        verify(removePublisher).remove(any(), isNull());
    }
}
