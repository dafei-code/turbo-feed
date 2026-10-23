package com.turbofeed.gateway.service.comment;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * opt-in 分支：经 RocketMQ 顺序投递评论创建事件到 {@link CommentPersistConsumer} 削峰。
 * 顺序键 = mediaId，使同内容的评论落库局部有序（与计数增量 postId 同语义）。fail-open：
 * 发送失败只告警，不影响发布主流程。
 *
 * <p>非 {@code @Component}：Bean 由 {@code CommentPublisherConfig.MqBranch} 在
 * {@code turbofeed.mq.enabled=true} 时声明，与 {@link LocalCommentEventPublisher} 互斥。
 * 该分支仅在 {@code --spring.profiles.active=mq} 激活时成立（RocketMQTemplate Bean 才存在）。</p>
 */
public class RocketMqCommentEventPublisher implements CommentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqCommentEventPublisher.class);
    public static final String TOPIC = "turbofeed-comment-created";

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMqCommentEventPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void publish(Comment c) {
        try {
            rocketMQTemplate.syncSendOrderly(TOPIC, CommentCreatedEvent.from(c), c.mediaId());
        } catch (Exception e) {
            log.warn("评论创建事件投递 MQ 失败（fail-open，不影响发布主流程）: mediaId={}, commentId={}, {}",
                    c.mediaId(), c.commentId(), e.getMessage());
        }
    }
}
