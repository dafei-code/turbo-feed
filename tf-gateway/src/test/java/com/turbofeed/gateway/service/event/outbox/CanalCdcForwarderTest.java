package com.turbofeed.gateway.service.event.outbox;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Canal→MQ 转发器单测（纯函数 parseEvent，不依赖 Canal / MQ 进程）。
 *
 * <p>验证：① INSERT + outbox_event 表 → 正确提取 eventId/aggregateId/traceId；
 * ② 非目标表 / 非 INSERT / 非 ROWDATA → 跳过；③ payload 无 traceId → 返回 null traceId；
 * ④ 坏 payload JSON 不影响 eventId 提取；⑤ 构造函数参数钳制。</p>
 */
@ExtendWith(MockitoExtension.class)
class CanalCdcForwarderTest {

    @Mock CanalConnectorFactory connectorFactory;
    @Mock RocketMQTemplate rocketMQTemplate;

    private CanalCdcForwarder forwarder;

    @BeforeEach
    void setUp() {
        forwarder = new CanalCdcForwarder(connectorFactory, rocketMQTemplate,
                new ObjectMapper(), "turbofeed-outbox-cdc", 100, 1000L, 3);
    }

    private CanalEntry.Entry insertEntry(String table, String id, String aggregateId, String payload) {
        CanalEntry.RowData row = CanalEntry.RowData.newBuilder()
                .addAfterColumns(col("id", id))
                .addAfterColumns(col("aggregate_id", aggregateId))
                .addAfterColumns(col("payload", payload))
                .build();
        CanalEntry.RowChange change = CanalEntry.RowChange.newBuilder()
                .setEventType(CanalEntry.EventType.INSERT)
                .addRowDatas(row)
                .build();
        return CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setTableName(table).build())
                .setStoreValue(change.toByteString())
                .build();
    }

    private CanalEntry.Column col(String name, String value) {
        return CanalEntry.Column.newBuilder().setName(name).setValue(value).build();
    }

    @Test
    void parseEvent_insert_extractsIdAggregateAndTrace() {
        CanalEntry.Entry entry = insertEntry("outbox_event_4", "1789889422631777",
                "media/1/foo.jpg", "{\"traceId\":\"abc-123\"}");

        CanalCdcForwarder.ForwardEvent event = forwarder.parseEvent(entry);

        assertNotNull(event);
        assertEquals(1789889422631777L, event.eventId());
        assertEquals("media/1/foo.jpg", event.aggregateId());
        assertEquals("abc-123", event.traceId());
    }

    @Test
    void parseEvent_nonOutboxTable_skipped() {
        CanalEntry.Entry entry = insertEntry("media_4", "1", "x", "{}");
        assertNull(forwarder.parseEvent(entry));
    }

    @Test
    void parseEvent_nonInsert_skipped() {
        CanalEntry.RowChange change = CanalEntry.RowChange.newBuilder()
                .setEventType(CanalEntry.EventType.DELETE)
                .addRowDatas(CanalEntry.RowData.newBuilder()
                        .addAfterColumns(col("id", "1")).build())
                .build();
        CanalEntry.Entry entry = CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setTableName("outbox_event_4").build())
                .setStoreValue(change.toByteString())
                .build();
        assertNull(forwarder.parseEvent(entry));
    }

    @Test
    void parseEvent_noTraceId_returnsNullTrace() {
        CanalEntry.Entry entry = insertEntry("outbox_event_102", "401232572132233216",
                "post/1", "{\"foo\":\"bar\"}");
        CanalCdcForwarder.ForwardEvent event = forwarder.parseEvent(entry);
        assertNotNull(event);
        assertEquals(401232572132233216L, event.eventId());
        assertNull(event.traceId());
    }

    @Test
    void parseEvent_badJsonPayload_stillExtractsId() {
        CanalEntry.Entry entry = insertEntry("outbox_event_4", "42", "post/2", "not-json");
        CanalCdcForwarder.ForwardEvent event = forwarder.parseEvent(entry);
        assertNotNull(event);
        assertEquals(42L, event.eventId());
        assertNull(event.traceId()); // traceId 是诊断字段，坏 JSON 不影响 eventId
    }

    @Test
    void parseEvent_transactionEntry_skipped() {
        CanalEntry.Entry entry = CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.TRANSACTIONBEGIN)
                .build();
        assertNull(forwarder.parseEvent(entry));
    }
}
