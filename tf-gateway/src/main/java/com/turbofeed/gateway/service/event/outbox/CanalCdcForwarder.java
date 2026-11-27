package com.turbofeed.gateway.service.event.outbox;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.trace.TraceContext;
import com.turbofeed.shared.trace.TraceIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Canal → RocketMQ 转发器（P2 CDC 链路的「最后一公里」，Opt-in 默认关闭）。
 *
 * <h3>它做什么</h3>
 * <p>连 Canal Server 拉取 {@code outbox_event} 分片表的 INSERT entry，把每行转成
 * {@link OutboxCdcEvent}（只含 eventId + traceId）发到 {@code turbofeed-outbox-cdc} Topic，
 * <b>用 aggregate_id 做 RocketMQ 分区键</b>保证同一帖的 APPEND/REMOVE 有序。网关侧的
 * {@link OutboxCdcConsumer} 收到消息后按 id 回查 DB 并 {@code tryDeliver}。</p>
 *
 * <h3>为什么消息体只有 eventId</h3>
 * <p>payload 的唯一事实源是 DB 行（见 {@link OutboxCdcConsumer} 类注释）。转发器只从 binlog
 * 的 after 镜像里取主键 id 和 payload 里的 traceId——traceId 仅用于链路串联，投递内容一律回查 DB。</p>
 *
 * <h3>可靠性与死循环防护</h3>
 * <ul>
 *   <li><b>正常批次</b>：全部处理完（含坏消息记录）后 {@code ack(batchId)} 推进位点；</li>
 *   <li><b>MQ 发送失败</b>：{@code rollback(batchId)} 让下轮重拉，同时按批累计失败次数；
 *       超过 {@code max-rollback-rounds} 后强制 ack + 告警（避免整个 canal 流被一条坏消息永久卡死，
 *       此时由 Relay 兜底补偿该事件）；</li>
 *   <li><b>解析失败（坏消息）</b>：记录 + 计数后照常 ack——一条解析不了的行不该阻塞后续所有事件。</li>
 * </ul>
 *
 * <p><b>开关</b>：{@code turbofeed.outbox.cdc-forwarder-enabled=true}（默认关）。
 * 多实例部署时只应在一个实例打开（Canal destination 是单订阅模型，多 client 会互相抢消费）。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.outbox.cdc-forwarder-enabled", havingValue = "true")
public class CanalCdcForwarder {

    private static final Logger log = LoggerFactory.getLogger(CanalCdcForwarder.class);

    static final String METRIC_FORWARDED = "tf_outbox_cdc_forwarded_total";
    static final String METRIC_BAD = "tf_outbox_cdc_bad_entry_total";
    static final String METRIC_ROLLBACK = "tf_outbox_cdc_rollback_total";

    private final CanalConnectorFactory connectorFactory;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final int batchSize;
    private final long pollTimeoutMs;
    private final int maxRollbackRounds;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /** 连续 rollback 轮数（单实例内存态；超过上限强制 ack 让坏消息不卡死整个流）。 */
    private int rollbackRounds;

    public CanalCdcForwarder(CanalConnectorFactory connectorFactory,
                             RocketMQTemplate rocketMQTemplate,
                             ObjectMapper objectMapper,
                             @Value("${turbofeed.outbox.cdc.topic:turbofeed-outbox-cdc}") String topic,
                             @Value("${turbofeed.outbox.cdc.forwarder.batch-size:100}") int batchSize,
                             @Value("${turbofeed.outbox.cdc.forwarder.poll-timeout-ms:1000}") long pollTimeoutMs,
                             @Value("${turbofeed.outbox.cdc.forwarder.max-rollback-rounds:3}") int maxRollbackRounds) {
        this.connectorFactory = connectorFactory;
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.batchSize = Math.max(1, batchSize);
        this.pollTimeoutMs = Math.max(100, pollTimeoutMs);
        this.maxRollbackRounds = Math.max(1, maxRollbackRounds);
    }

    /**
     * 一轮转发。{@code fixedDelay}：上一轮没结束不开下一轮。
     * 每轮独立建立/关闭连接（短连接），避免长连接断开后无法自愈——Canal 是内网单实例，
     * 建连开销可忽略，换来的是「Canal 重启后自动重连」。
     */
    @Scheduled(initialDelayString = "${turbofeed.outbox.cdc.forwarder.initial-delay-ms:10000}",
               fixedDelayString = "${turbofeed.outbox.cdc.forwarder.poll-interval-ms:1000}")
    public void forward() {
        Map<String, String> previous = TraceContext.beginCycle();
        try {
            forwardInternal();
        } finally {
            TraceContext.end(previous);
        }
    }

    /** 实际转发逻辑，拆出便于单测直接调用（不依赖调度器）。 */
    void forwardInternal() {
        CanalConnector connector = connectorFactory.create();
        try {
            connector.connect();
            connector.subscribe();
            Message message = connector.get(batchSize, pollTimeoutMs, TimeUnit.MILLISECONDS);
            if (message == null || message.getEntries().isEmpty()) {
                return; // 空轮：不 ack 也不 rollback（Canal 的 batchId 未前进）
            }
            handleBatch(connector, message);
        } catch (Exception e) {
            // 连接/拉取异常：只告警，下一轮重建连接重试；绝不抛给调度线程。
            log.warn("CDC 转发拉取失败（下一轮重连重试）: {}", e.getMessage());
        } finally {
            try {
                connector.disconnect();
            } catch (Exception ignored) {
                // disconnect 失败不影响本轮已完成的 ack，忽略。
            }
        }
    }

    private void handleBatch(CanalConnector connector, Message message) {
        long batchId = message.getId();
        List<CanalEntry.Entry> entries = message.getEntries();
        int forwarded = 0;
        int bad = 0;
        boolean sendFailed = false;
        for (CanalEntry.Entry entry : entries) {
            ForwardEvent event = parseEvent(entry);
            if (event == null) {
                bad++; // 非 outbox_event / 非 INSERT / 解析失败：计数后跳过，不阻塞后续
                continue;
            }
            try {
                // 分区键 = aggregate_id：保证同一帖的 APPEND/REMOVE 在同一队列有序
                rocketMQTemplate.syncSendOrderly(topic,
                        new OutboxCdcEvent(event.eventId(), event.traceId()),
                        event.aggregateId());
                forwarded++;
            } catch (Exception e) {
                log.error("CDC 转发 MQ 发送失败: eventId={}, aggregateId={}, {}",
                        event.eventId(), event.aggregateId(), e.getMessage());
                sendFailed = true;
                break; // 一条失败就中止本批，rollback 后整批重拉
            }
        }
        if (bad > 0) {
            increment(METRIC_BAD, bad);
            log.warn("CDC 转发本批跳过坏 entry {} 条", bad);
        }
        if (sendFailed) {
            rollbackRounds++;
            increment(METRIC_ROLLBACK, 1);
            if (rollbackRounds <= maxRollbackRounds) {
                connector.rollback(batchId);
                log.warn("CDC 转发 rollback 批次 {}（第 {}/{} 轮）", batchId, rollbackRounds, maxRollbackRounds);
                return;
            }
            // 超过上限：强制 ack，避免一条永久失败的消息卡死整个 canal 流；
            // 该事件由 OutboxRelay 兜底补偿（事件行仍在库里，不会丢）。
            log.error("CDC 转发连续 rollback {} 轮仍失败，强制 ack 批次 {} 放行（依赖 Relay 兜底）",
                    rollbackRounds, batchId);
            rollbackRounds = 0;
        } else {
            rollbackRounds = 0; // 本批成功，重置连续失败计数
        }
        connector.ack(batchId);
        if (forwarded > 0) {
            increment(METRIC_FORWARDED, forwarded);
            log.info("CDC 转发一轮: batch={}, forwarded={}, bad={}", batchId, forwarded, bad);
        }
    }

    /**
     * 解析一条 Canal entry → 转发事件；非目标表/非 INSERT/解析失败均返回 null。
     * 纯函数（只读 entry），便于单测直接构造 entry 验证。
     */
    ForwardEvent parseEvent(CanalEntry.Entry entry) {
        try {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                return null;
            }
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            if (rowChange.getEventType() != CanalEntry.EventType.INSERT) {
                return null;
            }
            String tableName = entry.getHeader().getTableName();
            if (tableName == null || !tableName.startsWith("outbox_event")) {
                return null;
            }
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                Map<String, String> cols = new java.util.HashMap<>();
                for (CanalEntry.Column col : rowData.getAfterColumnsList()) {
                    cols.put(col.getName(), col.getValue());
                }
                String idStr = cols.get("id");
                if (idStr == null || idStr.isEmpty()) {
                    continue;
                }
                long eventId = Long.parseLong(idStr);
                String aggregateId = cols.getOrDefault("aggregate_id", "");
                String traceId = extractTraceId(cols.get("payload"));
                return new ForwardEvent(eventId, aggregateId, traceId);
            }
        } catch (Exception e) {
            log.warn("CDC entry 解析失败，跳过: {}", e.getMessage());
        }
        return null;
    }

    /** 从 payload JSON 顶层取 traceId（与 OutboxService.payloadTraceId 同语义，缺字段返回 null）。 */
    private String extractTraceId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode trace = root == null ? null : root.get("traceId");
            if (trace == null || trace.isNull()) {
                return null;
            }
            return TraceIds.sanitize(trace.asText());
        } catch (Exception e) {
            return null; // traceId 是诊断字段，坏 JSON 不影响投递（Handler 会再解析并报错重试）
        }
    }

    private void increment(String name, int count) {
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }
        try {
            Counter.builder(name).description("CDC 转发器累计值").register(registry).increment(count);
        } catch (Exception e) {
            log.warn("CDC 转发指标记录失败（忽略）: {}", e.getMessage());
        }
    }

    /** 转发事件的中间投影（从 binlog after 镜像提取的最小字段）。 */
    record ForwardEvent(long eventId, String aggregateId, String traceId) {
    }
}
