package com.turbofeed.gateway.service.counter;

/**
 * 计数增量发布器（网关 → tf-counter 聚合底座）。
 *
 * <p>把「一次实际点赞状态变化」转成一条计数增量（{@code postId + LIKE + ±1}）发给 tf-counter，
 * 与网关实时计数（Redis 64 槽 Set）解耦——tf-counter 只做聚合底座，不抢权威计数。
 * 两条实现：{@link HttpCounterEventPublisher}（默认兜底）/ {@link RocketMqCounterEventPublisher}（MQ 削峰）。</p>
 */
public interface CounterEventPublisher {
    void incr(String postId, String type, int delta);
}
