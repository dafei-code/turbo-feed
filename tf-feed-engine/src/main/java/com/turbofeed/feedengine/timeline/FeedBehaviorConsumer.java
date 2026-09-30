package com.turbofeed.feedengine.timeline;

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
        if ("WATCH".equalsIgnoreCase(msg.type())) {
            postStatService.recordWatch(msg.timelineKey(), msg.watchDuration(), msg.mediaDuration());
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
