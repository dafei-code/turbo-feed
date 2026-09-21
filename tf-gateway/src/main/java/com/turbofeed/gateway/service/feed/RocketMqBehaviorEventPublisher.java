package com.turbofeed.gateway.service.feed;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 行为埋点的 <b>RocketMQ</b> 实现（{@code turbofeed.mq.enabled=true} 时激活）。
 *
 * <p>逐条 {@code syncSendOrderly(topic, event, postId)}：顺序键用 postId，使同一帖的埋点
 * 落到同队列、局部有序，利于单帖统计。与时间线投递不同，这里<b>失败 fail-open</b>（catch 后仅告警）——
 * 埋点是推荐优化项，不应让"上报一次互动"的异常冒泡成用户接口 500。
 * （时间线投递 fail-fast 是因为它关系内容正确性、要靠事务回滚兜底；埋点无此约束。）</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
public class RocketMqBehaviorEventPublisher implements BehaviorEventPublisher {

    /** 行为埋点 Topic，与引擎侧 {@code FeedBehaviorConsumer} 共用同一配置键。 */
    public static final String TOPIC = "turbofeed-feed-behavior";

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${turbofeed.feed.behavior.topic:turbofeed-feed-behavior}")
    private String topic;

    public RocketMqBehaviorEventPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void report(List<BehaviorReport> reports) {
        if (reports == null) {
            return;
        }
        for (BehaviorReport r : reports) {
            if (r == null || r.postId() == null) {
                continue;
            }
            try {
                rocketMQTemplate.syncSendOrderly(topic, new FeedBehaviorEvent(r.postId(), r.type()), r.postId());
            } catch (Exception e) {
                // 非关键路径：MQ 不可用时静默丢弃埋点，仅告警
                org.slf4j.LoggerFactory.getLogger(RocketMqBehaviorEventPublisher.class)
                        .warn("行为埋点 MQ 投递失败（fail-open，不影响主流程）: postId={}, {}", r.postId(), e.getMessage());
            }
        }
    }
}
