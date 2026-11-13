package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4：发件箱 payload 必须把 traceId 落进 JSON，且正反两个方向兼容。
 *
 * <p>这里锁住的是补偿路径（{@code OutboxRelay}）特有的风险：它从库里读 JSON 再重新构造事件，
 * 与「把原事件对象原样重发」的 DLQ 重放不同。payload 里没有 id，中继重投就必然丢链路，
 * 而且这种丢失只在 MQ 抖动后才出现，平时测试全绿。</p>
 */
class OutboxPayloadTraceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("APPEND / REMOVE payload 序列化后带 traceId，读回不丢")
    void payload_persistsTraceId() throws Exception {
        String appendJson = MAPPER.writeValueAsString(
                new TimelineAppendPayload(null, 3, "probe-abc123"));
        assertTrue(appendJson.contains("\"traceId\":\"probe-abc123\""));
        assertEquals("probe-abc123",
                MAPPER.readValue(appendJson, TimelineAppendPayload.class).traceId());

        String removeJson = MAPPER.writeValueAsString(
                new TimelineRemovePayload("post/1/x", "probe-abc123"));
        assertTrue(removeJson.contains("\"traceId\":\"probe-abc123\""));
        assertEquals("probe-abc123",
                MAPPER.readValue(removeJson, TimelineRemovePayload.class).traceId());
    }

    @Test
    @DisplayName("存量事件行（无 traceId 字段）能被新代码读出，字段为 null")
    void legacyPayload_withoutTraceId_isTolerated() throws Exception {
        TimelineAppendPayload append = MAPPER.readValue(
                "{\"item\":null,\"poolLevel\":1}", TimelineAppendPayload.class);
        assertNull(append.traceId());
        assertEquals(1, append.poolLevel());

        TimelineRemovePayload remove = MAPPER.readValue(
                "{\"timelineKey\":\"post/1/x\"}", TimelineRemovePayload.class);
        assertNull(remove.traceId());
        assertEquals("post/1/x", remove.timelineKey());
    }

    @Test
    @DisplayName("灰度期旧消费者：Boot 口径忽略未知字段，新 payload 不会打断旧节点")
    void legacyConsumer_ignoresNewTraceIdField() throws Exception {
        ObjectMapper bootLike = MAPPER.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertFalse(bootLike.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));

        String newJson = MAPPER.writeValueAsString(
                new TimelineRemovePayload("post/1/x", "probe-abc123"));
        LegacyRemovePayload seenByOldNode = bootLike.readValue(newJson, LegacyRemovePayload.class);
        assertEquals("post/1/x", seenByOldNode.timelineKey());
    }

    /** 旧版本消费者视角：故意没有 traceId 字段。 */
    private record LegacyRemovePayload(String timelineKey) {
    }
}
