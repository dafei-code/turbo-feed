package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.trace.TraceContext;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CDC 流式投递消费者（P2，Opt-in 灰度）。
 *
 * <p><b>它做什么</b>：Canal 订阅 {@code outbox_event} 的 INSERT，经转发器写到
 * {@code turbofeed-outbox-cdc} Topic（按 {@code aggregate_id} 做分区键，保证同一帖的
 * APPEND/REMOVE 有序）。本消费者收到消息后<b>只取 eventId</b>，交给
 * {@link OutboxService#tryDeliver(long)} 投递。</p>
 *
 * <p><b>为什么只传 eventId，不传完整事件体</b>：事件体的唯一事实源是 DB 里的那一行
 * （payload 含 occurredAt / traceId 等防旧与追踪字段）。传完整体就会出现「MQ 里是旧版本、
 * DB 里已更新」的分叉；只传 id 则每次投递都回查 DB，天然读到最新快照，且消息体极小。</p>
 *
 * <p><b>幂等靠 claim，不靠消费去重</b>：{@code tryDeliver} 内
 * {@code OutboxEventRepository.claim} 用 CAS（PENDING/FAILED → SENDING）保证同一行只被一个
 * 投递者领走。因此快路径（afterCommit）、OutboxRelay（轮询补偿）、CDC 消费者三者可以同时开，
 * 重复到达的消息只会有一个领到，其余 CAS 落空直接返回——不需要额外去重表。</p>
 *
 * <p><b>开关</b>：{@code turbofeed.outbox.cdc-consumer-enabled=false}（默认关）。
 * 与 {@code turbofeed.mq.enabled} 独立：CDC 是发件箱的传输通道选项，不是全局 MQ 开关；
 * 灰度时可以先只开 CDC 消费者，Relay 保留为兜底（轮询周期可调大）。</p>
 *
 * <p><b>容器未创建时</b>：本 Bean 仍会注册监听导致连不上 name-server。因此除开关外还需
 * {@code turbofeed.mq.enabled=true} 提供 name-server（CDC 依赖 MQ 基础设施）。灰度步骤见
 * {@code docs/ops/outbox-cdc-rollout.md}。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.outbox.cdc-consumer-enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${turbofeed.outbox.cdc.topic:turbofeed-outbox-cdc}",
        consumerGroup = "${turbofeed.outbox.cdc.consumer-group:turbofeed-outbox-cdc-group}",
        consumeMode = ConsumeMode.ORDERLY,
        maxReconsumeTimes = 5)
public class OutboxCdcConsumer implements RocketMQListener<OutboxCdcEvent> {

    private static final Logger log = LoggerFactory.getLogger(OutboxCdcConsumer.class);

    private final OutboxService outboxService;

    public OutboxCdcConsumer(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Override
    public void onMessage(OutboxCdcEvent event) {
        Map<String, String> previous = TraceContext.begin(event == null ? null : event.traceId());
        try {
            handle(event);
        } finally {
            TraceContext.end(previous);
        }
    }

    private void handle(OutboxCdcEvent event) {
        if (event == null || event.eventId() <= 0) {
            // 坏消息：上抛触发重试/DLQ，不静默吞——吞掉等于「事件永不被投递」且无告警。
            throw new IllegalArgumentException("CDC 事件缺少合法 eventId，无法投递");
        }
        try {
            boolean delivered = outboxService.tryDeliver(event.eventId());
            // delivered=false 有三种正常情况：已被快路径/Relay/其它实例投过（CAS 落空）、
            // 已是终态、或本次投递失败留待重试。后一种由 tryDeliver 内部记日志与指标，此处不重复。
            log.debug("CDC 投递处理完成: eventId={}, delivered={}", event.eventId(), delivered);
        } catch (Exception e) {
            log.error("CDC 投递异常（上抛触发 RocketMQ 重试/DLQ）: eventId={}, {}", event.eventId(), e.getMessage(), e);
            throw new IllegalStateException("CDC 投递失败，触发 MQ 重试", e);
        }
    }
}
