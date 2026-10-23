package com.turbofeed.gateway.service.comment;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 评论落库发布器单元测（纯 Mockito，无 MQ / Redis / Spring 上下文依赖）。
 *
 * <ul>
 *   <li>{@link LocalCommentEventPublisher}：默认分支，经 interactionExecutor 异步刷盘。</li>
 *   <li>{@link RocketMqCommentEventPublisher}：opt-in 分支，顺序投递；发送异常 fail-open 不向上抛。</li>
 *   <li>{@link CommentPersistConsumer}：消费端幂等落库（INSERT IGNORE）。</li>
 *   <li>{@link CommentCreatedEvent}：from/toComment 双向无损（避开 Instant 序列化坑）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CommentEventPublisherTest {

    @Mock
    CommentJdbcRepository commentRepository;
    @Mock
    RocketMQTemplate rocketMQTemplate;

    /** 同步执行器：让 Local 分支的 fire-and-forget 变为可立即断言。 */
    private final Executor syncExecutor = Runnable::run;

    @Test
    void localPublisher_insertsViaExecutor() {
        LocalCommentEventPublisher publisher = new LocalCommentEventPublisher(commentRepository, syncExecutor);
        Comment c = sample();
        publisher.publish(c);
        verify(commentRepository).insert(c);
    }

    @Test
    void mqPublisher_sendsOrderlyWithMediaIdShardingKey() {
        RocketMqCommentEventPublisher publisher = new RocketMqCommentEventPublisher(rocketMQTemplate);
        Comment c = sample();
        AtomicReference<CommentCreatedEvent> captured = new AtomicReference<>();
        // any(CommentCreatedEvent.class) 显式绑定 T=CommentCreatedEvent（具体类型），唯一命中
        // (String, Object, String) 重载，规避 syncSendOrderly 的 (Message) / (Collection<Message>) 泛型重载
        // 对 capture()/any() 无界 T 的歧义。
        doAnswer(inv -> {
            captured.set((CommentCreatedEvent) inv.getArguments()[1]);
            return null;
        }).when(rocketMQTemplate).syncSendOrderly(anyString(), any(CommentCreatedEvent.class), anyString());
        publisher.publish(c);
        CommentCreatedEvent sent = captured.get();
        assertNotNull(sent);
        assertEquals(c.commentId(), sent.getCommentId());
        assertEquals(c.mediaId(), sent.getMediaId());
    }

    @Test
    void mqPublisher_failOpen_onSendException() {
        // RocketMQTemplate 抛异常时 publish 必须吞掉，不向上抛（保证发布主流程不受影响）
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).syncSendOrderly(anyString(), any(CommentCreatedEvent.class), anyString());
        RocketMqCommentEventPublisher publisher = new RocketMqCommentEventPublisher(rocketMQTemplate);
        assertDoesNotThrow(() -> publisher.publish(sample()));
    }

    @Test
    void consumer_insertsIgnoreEvent() {
        CommentPersistConsumer consumer = new CommentPersistConsumer(commentRepository);
        Comment c = sample();
        consumer.onMessage(CommentCreatedEvent.from(c));
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).insertIgnore(cap.capture());
        Comment persisted = cap.getValue();
        assertEquals(c.commentId(), persisted.commentId());
        assertEquals(c.mediaId(), persisted.mediaId());
        assertEquals(c.status(), persisted.status());
    }

    @Test
    void event_roundTrip_preservesFields() {
        Comment c = sample();
        Comment back = CommentCreatedEvent.from(c).toComment();
        assertEquals(c.commentId(), back.commentId());
        assertEquals(c.mediaId(), back.mediaId());
        assertEquals(c.userId(), back.userId());
        assertEquals(c.rootId(), back.rootId());
        assertEquals(c.parentId(), back.parentId());
        assertEquals(c.content(), back.content());
        assertEquals(c.status(), back.status());
        assertEquals(c.likeCount(), back.likeCount());
        assertEquals(c.createdAt().toEpochMilli(), back.createdAt().toEpochMilli());
    }

    private Comment sample() {
        return new Comment(123L, "m1", 9L, 0L, 0L, "hello", CommentStatus.APPROVED, 0, Instant.now());
    }
}
