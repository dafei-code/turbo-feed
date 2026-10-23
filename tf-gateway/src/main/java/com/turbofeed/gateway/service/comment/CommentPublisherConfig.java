package com.turbofeed.gateway.service.comment;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;

/**
 * 评论落库发布器装配（削峰 opt-in 收敛底座）。
 *
 * <p>总闸 {@code turbofeed.mq.enabled}：false/缺省 → {@link LocalCommentEventPublisher}（进程内
 * 异步落库，零回归）；true → {@link RocketMqCommentEventPublisher}（MQ 削峰）。二者互斥，保证任意
 * 部署下只存在一个 {@link CommentEventPublisher} Bean（避免 ObjectProvider#ifAvailable 多 Bean 歧义）。</p>
 *
 * <p>条件组合用「嵌套 static @Configuration」实现 AND（{@code @ConditionalOnProperty} 本身不可重复
 * 叠两个，会编译报错）。</p>
 */
@Configuration
public class CommentPublisherConfig {

    /** 默认分支：MQ 关闭/缺失 → 进程内 interactionExecutor 异步落库。 */
    @Configuration
    @ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "false", matchIfMissing = true)
    static class LocalBranch {
        @Bean
        public LocalCommentEventPublisher localCommentEventPublisher(
                CommentJdbcRepository commentRepository,
                @Qualifier("interactionExecutor") Executor interactionExecutor) {
            return new LocalCommentEventPublisher(commentRepository, interactionExecutor);
        }
    }

    /** opt-in 分支：MQ 开启 → 顺序键 mediaId 投递削峰。 */
    @Configuration
    @ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
    static class MqBranch {
        @Bean
        public RocketMqCommentEventPublisher rocketMqCommentEventPublisher(RocketMQTemplate rocketMQTemplate) {
            return new RocketMqCommentEventPublisher(rocketMQTemplate);
        }
    }
}
