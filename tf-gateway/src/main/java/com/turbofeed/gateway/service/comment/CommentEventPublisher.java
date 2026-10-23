package com.turbofeed.gateway.service.comment;

/**
 * 评论落库事件发布器（发布/订阅解耦，opt-in 削峰）。
 *
 * <p>把一条已通过内容安全/风控校验的评论交给「落库执行器」：默认分支
 * {@link LocalCommentEventPublisher} 走进程内 interactionExecutor 异步刷盘（零回归）；
 * opt-in 分支 {@link RocketMqCommentEventPublisher} 经 RocketMQ 顺序投递到
 * {@link CommentPersistConsumer}，把瞬时写峰值削平为恒定消费速率。</p>
 *
 * <p>两种实现互斥（见 {@code CommentPublisherConfig}），任意部署下只存在一个 Bean，
 * 避免 {@code ObjectProvider#ifAvailable} 多 Bean 歧义。</p>
 */
public interface CommentEventPublisher {

    /** 发布一条待落库评论（实现自行决定本地异步还是 MQ 投递）。fail-open 由实现保证。 */
    void publish(Comment comment);
}
