package com.turbofeed.counter.mqconsumer;

import com.turbofeed.counter.service.CounterIncrService;
import com.turbofeed.shared.model.CounterIncrementEvent;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 计数增量 MQ 消费者（opt-in，{@code turbofeed.mq.enabled=true} 时激活）。
 *
 * <p>消费 {@code turbofeed-counter-incr} Topic（与网关 {@code RocketMqCounterEventPublisher} 共用），
 * 把削峰后的增量事件汇流到 {@link CounterIncrService}——与 HTTP 兜底通道同出口，
 * 保证「MQ 启用 / 关闭」两种部署下计数逻辑只有一套。</p>
 */
@Component
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.MQ_ENABLED, havingValue = "true")
@RocketMQMessageListener(topic = "turbofeed-counter-incr", consumerGroup = "tf-counter-incr")
public class CounterMqConsumer implements RocketMQListener<CounterIncrementEvent> {

    private final CounterIncrService incrService;

    public CounterMqConsumer(CounterIncrService incrService) {
        this.incrService = incrService;
    }

    @Override
    public void onMessage(CounterIncrementEvent e) {
        incrService.accept(e);
    }
}
