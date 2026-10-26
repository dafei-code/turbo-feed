package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.ContractVersion;
import com.turbofeed.shared.model.FeedBehaviorEvent;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.logging.BehaviorLogSink;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 行为埋点的 RocketMQ 消费者（{@code turbofeed.mq.enabled=true} 时激活）。
 *
 * <p>与网关 {@code RocketMqBehaviorEventPublisher} 共用 topic，逐条消费
 * （{@code CONCURRENTLY} 即可——行为埋点是累加统计，顺序无关，不需要 ORDERLY）。
 * 消费逻辑与 HTTP 路径完全一致：都委托 {@link PostStatService#record}。</p>
 *
 * <p><b>与时间线消费者的区别</b>：时间线事件必须 ORDERLY（同帖 append/remove 保序），
 * 埋点只需累加、无顺序依赖，故用并发消费提升吞吐。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${turbofeed.feed.behavior.topic:turbofeed-feed-behavior}",
        consumerGroup = "${turbofeed.feed.behavior.consumer-group:turbofeed-feed-behavior-group}",
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = 3)
public class FeedBehaviorConsumer implements RocketMQListener<FeedBehaviorEvent> {

    private final PostStatService postStatService;
    private final InterestService interestService;
    private final SessionSequenceService sessionSequenceService;
    private final BehaviorLogSink behaviorLogSink;

    public FeedBehaviorConsumer(PostStatService postStatService, InterestService interestService,
                                SessionSequenceService sessionSequenceService,
                                BehaviorLogSink behaviorLogSink) {
        this.postStatService = postStatService;
        this.interestService = interestService;
        this.sessionSequenceService = sessionSequenceService;
        this.behaviorLogSink = behaviorLogSink;
    }

    @Override
    public void onMessage(FeedBehaviorEvent msg) {
        if (msg == null) {
            return;
        }
        // M2/4.1 契约版本协商：行为埋点会被累进兴趣画像与 session 序列，属于「写进去就难撤回」
        // 的派生数据——按错误语义解释一条高版本消息，污染的是用户画像，比丢掉一条埋点严重得多。
        // 抛异常 → RocketMQ 重试耗尽后进 DLQ，留痕可巡检（绝不静默按当前语义处理）。
        ContractVersion.requireConsumable("FeedBehaviorEvent",
                FeedBehaviorEvent.SCHEMA_VERSION, msg.schemaVersion());
        if ("WATCH".equalsIgnoreCase(msg.type())) {
            postStatService.recordWatch(msg.timelineKey(), msg.watchDuration(), msg.mediaDuration());
        } else if ("UNLIKE".equalsIgnoreCase(msg.type())) {
            // 取消点赞：实时分 −1 + 热点榜按 LIKE 负权重回退（与 HTTP 路径成对修改）
            postStatService.cancel(msg.timelineKey(), msg.type());
        } else {
            postStatService.record(msg.timelineKey(), msg.type());
        }
        // 同一时刻累积兴趣画像（fail-open）；与 HTTP 接收端共用同一逻辑。
        interestService.accumulateFromEvent(msg);
        // 同步记录 session 级最近互动序列（fail-open）；与 FeedTimelineController#behavior 成对修改。
        sessionSequenceService.record(msg.userId(), msg.timelineKey(), interestService.eventWeight(msg));
        // M0：原始明细落盘；与 FeedTimelineController#behavior 保持一致（见该类 javadoc 的成对修改说明）。
        behaviorLogSink.log(msg);
    }
}
