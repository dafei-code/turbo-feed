package com.turbofeed.gateway.service;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import com.turbofeed.gateway.service.event.outbox.OutboxEventType;
import com.turbofeed.gateway.service.event.outbox.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-B：上传事务收口的接线护栏。
 *
 * <p>最关键的一条：{@code deliverAfterCommit} 被调用的那一刻，
 * {@code TransactionSynchronizationManager} 必须处于激活态——否则同类自调用 / 事务代理失效
 * 会让它退化成「无 afterCommit 屏障的立即投递」，PR-A 加的 WARN 就是为抓这种漏网。</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaUploadTransactionTest {

    @Mock private MediaJdbcRepository mediaRepository;
    @Mock private OutboxService outboxService;

    private MediaUploadedEvent event() {
        return new MediaUploadedEvent("post/42/abc", List.of("media/42/a.jpg"),
                List.of("http://cdn/a.jpg"), "42", "caption", "{}", "req-1",
                Instant.parse("2026-11-16T08:00:00Z"));
    }

    /** 不开真数据源，只让 TransactionTemplate 真正激活事务同步上下文的最小事务管理器。 */
    private static PlatformTransactionManager txManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                // no-op
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // no-op
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                // no-op
            }
        };
    }

    @Test
    void outboxEnabled_batchInsertEnqueueAndDeliverAfterCommit_insideOneTransaction() {
        when(outboxService.enqueue(eq(OutboxEventType.MEDIA_UPLOADED), eq("post/42/abc"), eq(42L), any()))
                .thenReturn(123L);
        // 在回调里断言同步上下文已激活（等价于「deliverAfterCommit 在事务方法内部调用」）。
        doAnswer(inv -> {
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive(),
                    "deliverAfterCommit 必须在事务内调用，否则 afterCommit 屏障失效");
            return null;
        }).when(outboxService).deliverAfterCommit(123L);

        new MediaUploadTransaction(txManager(), mediaRepository, outboxService, true)
                .persist(List.of(), event());

        InOrder order = inOrder(mediaRepository, outboxService);
        order.verify(mediaRepository).batchInsert(anyList());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        order.verify(outboxService).enqueue(eq(OutboxEventType.MEDIA_UPLOADED), eq("post/42/abc"),
                eq(42L), payload.capture());
        order.verify(outboxService).deliverAfterCommit(123L);

        // payload 直接复用 MediaUploadedEvent：它本来就带 occurredAt / traceId，无需再包一层。
        MediaUploadedEvent captured = assertInstanceOf(MediaUploadedEvent.class, payload.getValue());
        assertEquals("post/42/abc", captured.postId());
        assertEquals(Instant.parse("2026-11-16T08:00:00Z"), captured.occurredAt());
    }

    @Test
    void outboxDisabled_onlyBatchInsert_neverTouchesOutbox() {
        MediaUploadTransaction tx = new MediaUploadTransaction(
                txManager(), mediaRepository, outboxService, false);

        tx.persist(List.of(), event());

        verify(mediaRepository).batchInsert(anyList());
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
        verify(outboxService, never()).deliverAfterCommit(anyLong());
        assertTrue(!tx.isOutboxEnabled());
    }

    @Test
    void outboxEnabled_flagExposedToCaller_soLegacyDirectPublishIsSkipped() {
        assertTrue(new MediaUploadTransaction(txManager(), mediaRepository, outboxService, true)
                .isOutboxEnabled());
    }
}
