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
 * <p><b>R7：本类只做「解析消息 + 决定传输语义」，副作用全部交给
 * {@link FeedTimelineDeliveryService#applyStrict}</b>。改造前本类与 HTTP 通道各写一遍
 * 「时间线 + 标签索引 + 缓存失效」三条副作用，本类还<b>漏了缓存失效</b>——开 MQ 后新内容要等满
 * 缓存 TTL 才可见、下架内容仍被缓存继续返回。收敛到统一入口后，两个通道的副作用集合由同一段代码保证，
 * 新增通道在结构上无法漏调。</p>
 *
 * <p><b>必须走严格写入入口</b>：消费失败（抛异常）交给 RocketMQ 重试 / DLQ，
 * 因此调用 {@code applyStrict}（异常上抛），而非 fail-open 的 {@code apply}——否则异常被吞、
 * 重试与 DLQ 永不触发，静默丢失只是从 HTTP 搬到 MQ（等于白做 B2）。本类仅做 {@code log.error} 后原样 rethrow。</p>
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

    private final FeedTimelineDeliveryService feedTimelineDelivery;

    @Override
    public void onMessage(FeedTimelineEvent event) {
        try {
            feedTimelineDelivery.applyStrict(event);
        } catch (Exception e) {
            // 抛异常 → RocketMQ 按 maxReconsumeTimes 重投；重投耗尽进入死信队列 %DLQ%{consumerGroup}，
            // 由人工 / 巡检对账处理。严格写入入口保证异常能冒泡到此（fail-open 路径会吞掉）。
            log.error("Feed 时间线消费失败，触发 RocketMQ 重试/DLQ: timelineKey={}, action={}",
                    event == null ? null : event.timelineKey(),
                    event == null ? null : event.action(), e);
            throw e;
        }
    }
}
