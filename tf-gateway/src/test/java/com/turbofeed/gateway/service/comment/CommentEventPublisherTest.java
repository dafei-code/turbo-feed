package com.turbofeed.gateway.service.comment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论落库发布器单元测（纯 Mockito，无 MQ / Redis / Spring 上下文依赖）。
 *
 * <ul>
 *   <li>{@link LocalCommentEventPublisher}：默认分支，经 interactionExecutor 走严格幂等落库。</li>
 *   <li>{@link RocketMqCommentEventPublisher}：opt-in 分支顺序投递；发送异常不丢正文，转本地幂等兜底。</li>
 *   <li>{@link CommentPersistConsumer}：消费端严格幂等落库；DB 失败上抛以触发 MQ 重试 / DLQ。</li>
 *   <li>{@link CommentCreatedEvent}：from/toComment 双向无损（避开 Instant 序列化坑）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CommentEventPublisherTest {

    @Mock
    CommentJdbcRepository commentRepository;
    @Mock
    RocketMQTemplate rocketMQTemplate;
    @Mock
    CommentPageCache pageCache;

    /** 同步执行器：让 Local 分支的 fire-and-forget 变为可立即断言。 */
    private final Executor syncExecutor = Runnable::run;

    private RocketMqCommentEventPublisher mqPublisher() {
        return new RocketMqCommentEventPublisher(rocketMQTemplate, commentRepository, syncExecutor);
    }

    @Test
    void localPublisher_insertsViaIdempotentPath() {
        LocalCommentEventPublisher publisher = new LocalCommentEventPublisher(commentRepository, syncExecutor);
        Comment c = sample();
        publisher.publish(c);
        verify(commentRepository).insertIfAbsent(c);
    }

    @Test
    void localPublisher_swallowsDbFailureButKeepsPublishingFlow() {
        when(commentRepository.insertIfAbsent(any(Comment.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        LocalCommentEventPublisher publisher = new LocalCommentEventPublisher(commentRepository, syncExecutor);
        // 主流程 fail-open 仍成立（不能把 500 抛回发布请求），但日志已升级为 ERROR，见实现类
        assertDoesNotThrow(() -> publisher.publish(sample()));
    }

    @Test
    void mqPublisher_sendsOrderlyWithMediaIdShardingKey() {
        RocketMqCommentEventPublisher publisher = mqPublisher();
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
    void mqPublisher_fallsBackToLocalIdempotentPersist_onSendException() {
        // 发送失败不再只 WARN 就丢：broker 抖动/超时时正文必须仍有机会进 DB
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).syncSendOrderly(anyString(), any(CommentCreatedEvent.class), anyString());
        Comment c = sample();
        assertDoesNotThrow(() -> mqPublisher().publish(c));
        verify(commentRepository).insertIfAbsent(c);
    }

    @Test
    void mqPublisher_doesNotPersistLocally_whenSendSucceeds() {
        doAnswer(inv -> null)
                .when(rocketMQTemplate).syncSendOrderly(anyString(), any(CommentCreatedEvent.class), anyString());
        mqPublisher().publish(sample());
        verify(commentRepository, never()).insertIfAbsent(any(Comment.class));
    }

    @Test
    void consumer_insertsViaIdempotentPath() {
        CommentPersistConsumer consumer = new CommentPersistConsumer(commentRepository);
        Comment c = sample();
        consumer.onMessage(CommentCreatedEvent.from(c));
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).insertIfAbsent(cap.capture());
        Comment persisted = cap.getValue();
        assertEquals(c.commentId(), persisted.commentId());
        assertEquals(c.mediaId(), persisted.mediaId());
        assertEquals(c.status(), persisted.status());
    }

    @Test
    void consumer_rethrowsOnDbFailure_toTriggerMqRetry() {
        // strict 消费：异常必须上抛，spring-rocketmq 才会返回 RECONSUME_LATER；吞掉就等于 ack 丢消息
        when(commentRepository.insertIfAbsent(any(Comment.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        CommentPersistConsumer consumer = new CommentPersistConsumer(commentRepository);
        assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(CommentCreatedEvent.from(sample())));
    }

    @Test
    void consumer_rejectsNullEvent() {
        CommentPersistConsumer consumer = new CommentPersistConsumer(commentRepository);
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(null));
    }

    // ===== 优化项④：失效必须挂在「落库成功之后」，且绝不影响 ack 语义 =====

    @Test
    void localPublisher_evictsPageCache_onlyAfterSuccessfulInsert() {
        when(commentRepository.insertIfAbsent(any(Comment.class))).thenReturn(true);
        LocalCommentEventPublisher publisher =
                new LocalCommentEventPublisher(commentRepository, syncExecutor, pageCache);
        publisher.publish(sample());
        verify(pageCache).evict("m1");
    }

    @Test
    void localPublisher_doesNotEvict_onDuplicateOrDbFailure() {
        LocalCommentEventPublisher publisher =
                new LocalCommentEventPublisher(commentRepository, syncExecutor, pageCache);
        when(commentRepository.insertIfAbsent(any(Comment.class))).thenReturn(false);
        publisher.publish(sample());
        when(commentRepository.insertIfAbsent(any(Comment.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertDoesNotThrow(() -> publisher.publish(sample()));
        verify(pageCache, never()).evict(anyString());
    }

    @Test
    void mqPublisher_fallbackPersist_evictsPageCacheAfterInsert() {
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).syncSendOrderly(anyString(), any(CommentCreatedEvent.class), anyString());
        when(commentRepository.insertIfAbsent(any(Comment.class))).thenReturn(true);
        RocketMqCommentEventPublisher publisher = new RocketMqCommentEventPublisher(
                rocketMQTemplate, commentRepository, syncExecutor, pageCache);
        publisher.publish(sample());
        verify(pageCache).evict("m1");
    }

    @Test
    void consumer_evictsPageCacheAfterInsert_butNotOnDuplicate() {
        CommentPersistConsumer consumer = new CommentPersistConsumer(commentRepository, pageCache);
        when(commentRepository.insertIfAbsent(any(Comment.class))).thenReturn(true);
        consumer.onMessage(CommentCreatedEvent.from(sample()));
        verify(pageCache).evict("m1");

        when(commentRepository.insertIfAbsent(any(Comment.class))).thenReturn(false);
        consumer.onMessage(CommentCreatedEvent.from(sample()));
        verify(pageCache, org.mockito.Mockito.times(1)).evict(anyString());
    }

    @Test
    void consumer_cacheEvictFailure_neverTriggersMqRetry() {
        when(commentRepository.insertIfAbsent(any(Comment.class))).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(pageCache).evict(anyString());
        CommentPersistConsumer consumer = new CommentPersistConsumer(commentRepository, pageCache);
        // 缓存是旁路增强：即使 evict 抛异常，也不能上抛成 RECONSUME_LATER 造成重复落库
        assertDoesNotThrow(() -> consumer.onMessage(CommentCreatedEvent.from(sample())));
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

    @Test
    void event_traceId_roundTripAndLegacyCompatibility() throws Exception {
        // ① 新生产者：traceId 随消息体透传
        ObjectMapper bootLike = Jackson2ObjectMapperBuilder.json().build();
        String withTrace = bootLike.writeValueAsString(CommentCreatedEvent.from(sample(), "probe-abc123"));
        assertFalse(withTrace.isBlank());
        org.junit.jupiter.api.Assertions.assertTrue(withTrace.contains("\"traceId\":\"probe-abc123\""));
        assertEquals("probe-abc123", bootLike.readValue(withTrace, CommentCreatedEvent.class).getTraceId());

        // ② 新消费者吃旧消息：老格式没有 traceId 字段，@JsonCreator 必须容忍缺字段
        String legacy = "{\"commentId\":123,\"mediaId\":\"m1\",\"userId\":9,\"rootId\":0,\"parentId\":0,"
                + "\"content\":\"hello\",\"statusCode\":1,\"likeCount\":0,\"createdAtEpochMillis\":1}";
        CommentCreatedEvent oldMessage = bootLike.readValue(legacy, CommentCreatedEvent.class);
        assertNull(oldMessage.getTraceId());
        assertEquals(123L, oldMessage.getCommentId());
        assertEquals("m1", oldMessage.getMediaId());

        // ③ 灰度期旧消费者吃新消息：Spring 消息转换器口径默认忽略未知字段
        assertFalse(bootLike.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                "若这条变红，说明 MQ 消息转换器改成了严格模式，旧消费者会被新字段打断");
        LegacyCommentCreatedEvent seenByOldConsumer =
                bootLike.readValue(withTrace, LegacyCommentCreatedEvent.class);
        assertEquals(123L, seenByOldConsumer.commentId());
        assertEquals("m1", seenByOldConsumer.mediaId());
    }

    /** 旧版本消费者视角：故意没有 traceId 字段。 */
    private record LegacyCommentCreatedEvent(long commentId, String mediaId, long userId, long rootId,
                                             long parentId, String content, int statusCode, int likeCount,
                                             long createdAtEpochMillis) {
    }

    private Comment sample() {
        return new Comment(123L, "m1", 9L, 0L, 0L, "hello", CommentStatus.APPROVED, 0, Instant.now());
    }
}
