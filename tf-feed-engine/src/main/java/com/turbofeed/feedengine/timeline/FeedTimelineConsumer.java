package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedTimelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feed 时间线事件消费者（B2：RocketMQ 顺序消息）。
 *
 * <p>与网关 {@code RocketMqFeedTimelinePublisher} 共用同一 topic（{@code turbofeed-feed-timeline}），
 * 用消息体里的 {@code action} 区分 APPEND / REMOVE——<b>绝不按 action 拆 tag / 拆消费者</b>，
 * 否则同一帖的 append 与 remove 会落到不同队列、失去顺序保证，出现
 * 「remove 先执行、append 后执行 → 已下架内容重新出现」的内容安全事故。</p>
 *
 * <p><b>顺序性</b>：{@code consumeMode = ORDERLY} + 发送侧
 * {@code syncSendOrderly(..., hashKey = FeedItemView#timelineKey())}，保证同一帖的事件严格按序消费。
 * 键是<b>帖身份</b>（有 postId 用 postId，历史单图数据回退 mediaId），与 {@code FeedTimelineStore}
 * 的反查索引同口径。</p>
 *
 * <p><b>必须走严格写入入口</b>：消费失败（抛异常）交给 RocketMQ 重试 / DLQ，
 * 因此调用 {@link FeedTimelineStore#appendStrict} / {@link FeedTimelineStore#removeStrict}（异常上抛），
 * 而非 fail-open 的 {@code append}/{@code remove}——否则异常被吞、重试与 DLQ 永不触发，
 * 静默丢失只是从 HTTP 搬到 MQ（等于白做 B2）。本类仅做 {@code log.error} 后原样 rethrow。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${turbofeed.feed.timeline.topic:turbofeed-feed-timeline}",
        consumerGroup = "${turbofeed.feed.timeline.consumer-group:turbofeed-feed-timeline-group}",
        consumeMode = ConsumeMode.ORDERLY,
        maxReconsumeTimes = 5)
public class FeedTimelineConsumer implements RocketMQListener<FeedTimelineEvent> {

    private final FeedTimelineStore feedTimelineStore;

    @Override
    public void onMessage(FeedTimelineEvent event) {
        try {
            if (FeedTimelineEvent.ACTION_APPEND.equals(event.action())) {
                int pool = event.poolLevel() == null ? 1 : event.poolLevel();
                feedTimelineStore.appendStrict(event.item(), pool);
            } else if (FeedTimelineEvent.ACTION_REMOVE.equals(event.action())) {
                feedTimelineStore.removeStrict(event.timelineKey());
            } else {
                // 未知 action（action 为 null / 两侧版本不一致 / 消息损坏）。
                // 【绝不能走 else 兜底当成 REMOVE】—— 那会让一条坏消息把内容下架，是反方向的安全事故。
                // 抛异常交给 MQ 重试，重试耗尽进 DLQ 人工核对；本分支不改动任何数据。
                throw new IllegalArgumentException("未知的 Feed 时间线 action（拒绝按 REMOVE 兜底）: action="
                        + event.action() + ", timelineKey=" + event.timelineKey());
            }
        } catch (Exception e) {
            // 抛异常 → RocketMQ 按 maxReconsumeTimes 重投；重投耗尽进入死信队列 %DLQ%{consumerGroup}，
            // 由人工 / 巡检对账处理。严格写入入口保证异常能冒泡到此（fail-open 路径会吞掉）。
            log.error("Feed 时间线消费失败，触发 RocketMQ 重试/DLQ: timelineKey={}, action={}",
                    event.timelineKey(), event.action(), e);
            throw e;
        }
    }
}
