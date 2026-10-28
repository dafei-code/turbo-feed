package com.turbofeed.gateway.service.comment;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 评论创建事件消费者（opt-in，{@code turbofeed.mq.enabled=true} 时激活）。
 *
 * <p>消费 {@code turbofeed-comment-created} Topic（与网关 {@link RocketMqCommentEventPublisher} 共用），
 * 把削峰后的事件幂等落库（{@code INSERT IGNORE}，comment_id 主键保证同事件只落一次）。fail-open。</p>
 */
@Component
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.MQ_ENABLED, havingValue = "true")
@RocketMQMessageListener(topic = "turbofeed-comment-created", consumerGroup = "tf-comment-persist")
public class CommentPersistConsumer implements RocketMQListener<CommentCreatedEvent> {

    private static final Logger log = LoggerFactory.getLogger(CommentPersistConsumer.class);

    private final CommentJdbcRepository commentRepository;

    public CommentPersistConsumer(CommentJdbcRepository commentRepository) {
        this.commentRepository = commentRepository;
    }

    @Override
    public void onMessage(CommentCreatedEvent e) {
        try {
            commentRepository.insertIgnore(e.toComment());
        } catch (Exception ex) {
            log.warn("评论事件落库失败（fail-open）: commentId={}, {}", e.getCommentId(), ex.getMessage());
        }
    }
}
