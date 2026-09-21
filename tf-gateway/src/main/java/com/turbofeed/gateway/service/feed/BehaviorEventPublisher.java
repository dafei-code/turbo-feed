package com.turbofeed.gateway.service.feed;

import java.util.List;

/**
 * 行为埋点发布端口（防腐层出口），与 {@link FeedTimelinePublisher} 同构。
 *
 * <p>统一收网关的 {@link BehaviorReport}，对调用方屏蔽"投递到底用同步 HTTP 还是 RocketMQ"——
 * 由 {@code turbofeed.mq.enabled} 在 {@code HttpBehaviorEventPublisher} 与
 * {@code RocketMqBehaviorEventPublisher} 间二选一，业务层零改动。</p>
 */
public interface BehaviorEventPublisher {

    /**
     * 上报一批行为事件。fail-open：实现内部失败只告警，绝不抛给调用方
     * （埋点是推荐优化项，不能让"点个赞"失败影响用户主流程）。
     *
     * @param reports 行为事件列表（postId + 类型）
     */
    void report(List<BehaviorReport> reports);
}
