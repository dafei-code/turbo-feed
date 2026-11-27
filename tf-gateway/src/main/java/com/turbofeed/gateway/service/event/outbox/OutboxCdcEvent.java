package com.turbofeed.gateway.service.event.outbox;

/**
 * CDC 转发器投递的最小消息体：只带发件箱行 id + 原请求 traceId。
 *
 * <p>刻意不带完整事件体：payload 的唯一事实源是 DB 的 {@code outbox_event} 行
 * （见 {@link OutboxCdcConsumer} 类注释）。{@code aggregateId} 作为 Kafka/RocketMQ 分区键
 * 由转发器放在消息 key 上，保证同一帖事件有序，不必重复塞进消息体。</p>
 *
 * @param eventId 发件箱行 id（雪花，{@link OutboxService#tryDeliver} 的入参）
 * @param traceId 原请求 traceId，可为空（存量行没有该字段时由消费侧生成本地 id）
 */
public record OutboxCdcEvent(long eventId, String traceId) {
}
