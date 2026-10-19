package com.turbofeed.gateway.service.counter;

import com.turbofeed.shared.model.CounterIncrementEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * 计数增量 MQ 发布器（opt-in，{@code turbofeed.mq.enabled=true} 且
 * {@code turbofeed.counter.service.enabled=true} 时激活）。
 *
 * <p>顺序键用 {@code postId}，使同帖增量落到同队列、局部有序（与 {@code RocketMqBehaviorEventPublisher}
 * 同语义）。与 {@link HttpCounterEventPublisher} 互斥，保证「MQ 开/关」两种部署下只有一套发送逻辑。</p>
 */
public class RocketMqCounterEventPublisher implements CounterEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqCounterEventPublisher.class);
    public static final String TOPIC = "turbofeed-counter-incr";

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public RocketMqCounterEventPublisher(RocketMQTemplate rocketMQTemplate,
                                         @Value("${turbofeed.counter.service.topic:turbofeed-counter-incr}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    @Override
    public void incr(String postId, String type, int delta) {
        try {
            rocketMQTemplate.syncSendOrderly(topic, new CounterIncrementEvent(postId, type, delta), postId);
        } catch (Exception e) {
            log.warn("计数增量投递 tf-counter(MQ)失败（fail-open，不影响点赞主流程）: postId={}, type={}, delta={}, {}",
                    postId, type, delta, e.getMessage());
        }
    }
}
