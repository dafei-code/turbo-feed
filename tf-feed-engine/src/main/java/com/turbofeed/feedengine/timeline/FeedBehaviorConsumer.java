package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedBehaviorEvent;
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

    public FeedBehaviorConsumer(PostStatService postStatService) {
        this.postStatService = postStatService;
    }

    @Override
    public void onMessage(FeedBehaviorEvent msg) {
        if (msg == null) {
            return;
        }
        postStatService.record(msg.timelineKey(), msg.type());
    }
}
