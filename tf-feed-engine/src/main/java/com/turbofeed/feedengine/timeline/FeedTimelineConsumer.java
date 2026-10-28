package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedTimelineEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 *
 * <h3>M4：为什么这里必须有指标</h3>
 * <p>改造前失败只有一行 {@code log.error}。但「重投耗尽进 DLQ」是个<b>渐进的</b>过程：
 * 前 5 次失败都还在重试窗口里，第 6 次才进死信。只看日志没法回答最关键的问题——
 * <b>当前失败率有多高、是否正在滑向 DLQ</b>。补两个计数器：</p>
 * <ul>
 *   <li>{@code tf_feed_timeline_consume_total{action}} —— 消费尝试数（分母）；</li>
 *   <li>{@code tf_feed_timeline_consume_failed_total{action}} —— 失败数（分子）。</li>
 * </ul>
 * <p>只记失败数是不够的：没有分母就算不出失败率，而「每秒 3 次失败」在总量 3000/s 时是噪声、
 * 在总量 5/s 时是全线故障。告警规则应是 {@code rate(failed)/rate(total) > 阈值} 而非裸计数。</p>
 *
 * <p>{@code action} 标签取值只有 APPEND / REMOVE 两种（且 REMOVE 失败更急——下架没生效意味着
 * 内容还在公域可见），故按 action 分标签是安全的、且能分别配告警。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.MQ_ENABLED, havingValue = "true")
@RocketMQMessageListener(
        topic = "${turbofeed.feed.timeline.topic:turbofeed-feed-timeline}",
        consumerGroup = "${turbofeed.feed.timeline.consumer-group:turbofeed-feed-timeline-group}",
        consumeMode = ConsumeMode.ORDERLY,
        maxReconsumeTimes = 5)
public class FeedTimelineConsumer implements RocketMQListener<FeedTimelineEvent> {

    private final FeedTimelineDeliveryService feedTimelineDelivery;
    private final MeterRegistry registry;

    @Override
    public void onMessage(FeedTimelineEvent event) {
        // action 为空（消息体损坏）时打 unknown 而不是拼出 null 标签——Prometheus 标签空值
        // 会与「真的叫 null 的 action」混淆，且告警规则里难以表达。
        String action = event == null || event.action() == null ? "UNKNOWN" : event.action();
        try {
            feedTimelineDelivery.applyStrict(event);
            Counter.builder("tf_feed_timeline_consume_total")
                    .description("时间线事件消费尝试数（失败率的分母）")
                    .tag("action", action)
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            Counter.builder("tf_feed_timeline_consume_failed_total")
                    .description("时间线事件消费失败数（计入重试，达 maxReconsumeTimes 后进 DLQ）")
                    .tag("action", action)
                    .register(registry)
                    .increment();
            // 抛异常 → RocketMQ 按 maxReconsumeTimes 重投；重投耗尽进入死信队列 %DLQ%{consumerGroup}，
            // 由运维巡检 / 对账处理（runbook 见 docs/architecture/failure-mode-ledger.md §5.1）。
            log.error("Feed 时间线消费失败，触发 RocketMQ 重试/DLQ: timelineKey={}, action={}",
                    event == null ? null : event.timelineKey(),
                    event == null ? null : event.action(), e);
            throw e;
        }
    }
}
