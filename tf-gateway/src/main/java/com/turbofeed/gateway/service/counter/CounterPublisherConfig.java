package com.turbofeed.gateway.service.counter;

import com.turbofeed.gateway.config.CounterProperties;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 计数增量发布器装配（P1-2，opt-in 收敛底座）。
 *
 * <p>条件组合用「嵌套 static @Configuration」实现 AND（{@code @ConditionalOnProperty}
 * 本身不可重复，叠两个会编译报错）。外层开关 {@code turbofeed.counter.service.enabled=true}
 * 是总闸；内层按 {@code turbofeed.mq.enabled} 决定走 HTTP 兜底还是 MQ 削峰，二者互斥，
 * 保证任意部署下只存在一套 {@link CounterEventPublisher} Bean（避免 ObjectProvider#ifAvailable 多 Bean 歧义）。</p>
 */
@Configuration
public class CounterPublisherConfig {

    /** 总闸：计数聚合底座整体开启。 */
    @Configuration
    @ConditionalOnProperty(name = "turbofeed.counter.service.enabled", havingValue = "true")
    static class CounterServiceEnabledConfig {

        /** 默认分支：MQ 关闭/缺失 → HTTP 兜底（与 FeedEngineClient 同款防腐层）。 */
        @Configuration
        @ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "false", matchIfMissing = true)
        static class HttpBranch {
            @Bean
            public HttpCounterEventPublisher httpCounterEventPublisher(CounterProperties props,
                                                                        RestClient.Builder restClientBuilder,
                                                                        @Value("${turbofeed.internal.token:}") String internalToken) {
                return new HttpCounterEventPublisher(props, restClientBuilder, internalToken);
            }
        }

        /** opt-in 分支：MQ 开启 → 顺序键 postId 投递削峰。 */
        @Configuration
        @ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
        static class MqBranch {
            @Bean
            public RocketMqCounterEventPublisher rocketMqCounterEventPublisher(RocketMQTemplate rocketMQTemplate,
                    @Value("${turbofeed.counter.service.topic:turbofeed-counter-incr}") String topic) {
                return new RocketMqCounterEventPublisher(rocketMQTemplate, topic);
            }
        }
    }
}
