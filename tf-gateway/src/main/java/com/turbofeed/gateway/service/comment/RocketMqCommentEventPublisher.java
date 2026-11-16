package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.trace.TraceContext;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.Executor;

/**
 * opt-in 分支：经 RocketMQ 顺序投递评论创建事件到 {@link CommentPersistConsumer} 削峰。
 * 顺序键 = mediaId，使同内容的评论落库局部有序（与计数增量 postId 同语义）。
 *
 * <p><b>突发档可靠性（PR-A / 优化项①）</b>：发送失败不再只 WARN 就丢。broker 抖动或超时时
 * 事件可能根本没进 broker，也可能已进但响应丢失；两种情况下都改走本地幂等兜底落库，
 * 与消费端共用 {@link CommentJdbcRepository#insertIfAbsent}，重复投递只会命中「已存在」，
 * 不会双写。兜底仍失败则 ERROR 带 commentId 留痕，供对账补偿。</p>
 *
 * <p><b>优化项④ 接线</b>：MQ 正常投递时由消费端在落库成功后失效页缓存；本类只在
 * 「发送失败 → 本地兜底落库成功」这条分支自行失效，避免两条路径都失效或都不失效。</p>
 *
 * <p>非 {@code @Component}：Bean 由 {@code CommentPublisherConfig.MqBranch} 在
 * {@code turbofeed.mq.enabled=true} 时声明，与 {@link LocalCommentEventPublisher} 互斥。
 * 该分支仅在 {@code --spring.profiles.active=mq} 激活时成立（RocketMQTemplate Bean 才存在）。</p>
 */
public class RocketMqCommentEventPublisher implements CommentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqCommentEventPublisher.class);
    public static final String TOPIC = "turbofeed-comment-created";

    private final RocketMQTemplate rocketMQTemplate;
    private final CommentJdbcRepository commentRepository;
    private final Executor interactionExecutor;
    /** 可为 null（裁剪部署 / 单测直接 new 三参构造器）。 */
    private final CommentPageCache pageCache;

    /** 兼容既有调用方（含单测）：无缓存增强。 */
    public RocketMqCommentEventPublisher(RocketMQTemplate rocketMQTemplate,
                                         CommentJdbcRepository commentRepository,
                                         @Qualifier("interactionExecutor") Executor interactionExecutor) {
        this(rocketMQTemplate, commentRepository, interactionExecutor, null);
    }

    public RocketMqCommentEventPublisher(RocketMQTemplate rocketMQTemplate,
                                         CommentJdbcRepository commentRepository,
                                         @Qualifier("interactionExecutor") Executor interactionExecutor,
                                         CommentPageCache pageCache) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.commentRepository = commentRepository;
        this.interactionExecutor = interactionExecutor;
        this.pageCache = pageCache;
    }

    @Override
    public void publish(Comment c) {
        try {
            rocketMQTemplate.syncSendOrderly(TOPIC,
                    CommentCreatedEvent.from(c, TraceContext.current()), c.mediaId());
        } catch (Exception e) {
            log.error("评论创建事件投递 MQ 失败，转本地幂等兜底落库: mediaId={}, commentId={}",
                    c.mediaId(), c.commentId(), e);
            fallbackPersist(c);
        }
    }

    /** MQ 不可用时把正文先落进 DB，避免「Redis 已可见、DB 永远缺行」。 */
    private void fallbackPersist(Comment c) {
        try {
            interactionExecutor.execute(() -> {
                try {
                    boolean inserted = commentRepository.insertIfAbsent(c);
                    if (inserted) {
                        log.warn("MQ 发送失败→本地兜底落库成功（突发档降级留痕）: mediaId={}, commentId={}",
                                c.mediaId(), c.commentId());
                        evictPageCache(c.mediaId());
                    }
                } catch (Exception ex) {
                    log.error("MQ 发送失败且本地兜底落库也失败（评论正文确定丢失，需对账补偿）: mediaId={}, commentId={}",
                            c.mediaId(), c.commentId(), ex);
                }
            });
        } catch (Exception rejected) {
            log.error("本地兜底任务提交被拒，改同步兜底: mediaId={}, commentId={}",
                    c.mediaId(), c.commentId(), rejected);
            try {
                boolean inserted = commentRepository.insertIfAbsent(c);
                if (inserted) {
                    evictPageCache(c.mediaId());
                }
            } catch (Exception ex) {
                log.error("同步兜底落库失败（评论正文确定丢失）: mediaId={}, commentId={}",
                        c.mediaId(), c.commentId(), ex);
            }
        }
    }

    private void evictPageCache(String mediaId) {
        if (pageCache != null) {
            pageCache.evict(mediaId);
        }
    }
}
