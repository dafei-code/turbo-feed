package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.trace.TraceContext;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

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
        // 落库失败只 warn，日志是唯一的排查线索——必须带上原请求的 traceId，
        // 否则「评论计数 +1 但正文没进库」这类问题无法关联回哪次发布。
        Map<String, String> previous = TraceContext.begin(e == null ? null : e.getTraceId());
        try {
            commentRepository.insertIgnore(e.toComment());
        } catch (Exception ex) {
            log.warn("评论事件落库失败（fail-open）: commentId={}, {}", e.getCommentId(), ex.getMessage());
        } finally {
            TraceContext.end(previous);
        }
    }
}
