package com.turbofeed.gateway.service.comment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.Executor;

/**
 * 默认分支：进程内 interactionExecutor 异步落库（与 ② 点赞同款）。零回归——
 * 未启用 MQ 时评论落库路径与改造前完全一致。fail-open：落库异常只告警，不影响主流程。
 *
 * <p>非 {@code @Component}：Bean 由 {@code CommentPublisherConfig.LocalBranch} 在
 * {@code turbofeed.mq.enabled=false/缺省} 时声明，与 {@link RocketMqCommentEventPublisher} 互斥。</p>
 */
public class LocalCommentEventPublisher implements CommentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalCommentEventPublisher.class);

    private final CommentJdbcRepository commentRepository;
    private final Executor interactionExecutor;

    public LocalCommentEventPublisher(CommentJdbcRepository commentRepository,
                                       @Qualifier("interactionExecutor") Executor interactionExecutor) {
        this.commentRepository = commentRepository;
        this.interactionExecutor = interactionExecutor;
    }

    @Override
    public void publish(Comment c) {
        interactionExecutor.execute(() -> {
            try {
                commentRepository.insert(c);
            } catch (Exception ex) {
                log.warn("评论异步落库失败（fail-open，不影响主流程）: mediaId={}, commentId={}, {}",
                        c.mediaId(), c.commentId(), ex.getMessage());
            }
        });
    }
}
