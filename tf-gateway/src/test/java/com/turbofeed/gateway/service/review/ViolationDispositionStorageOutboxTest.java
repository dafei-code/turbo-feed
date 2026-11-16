package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.behavior.BehaviorLogService;
import com.turbofeed.gateway.service.event.outbox.OutboxEventType;
import com.turbofeed.gateway.service.event.outbox.OutboxService;
import com.turbofeed.gateway.service.event.outbox.StorageDeletePayload;
import com.turbofeed.gateway.service.health.AccountHealthService;
import com.turbofeed.gateway.service.penalty.PenaltyService;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.signal.ModerationSignalService;
import com.turbofeed.gateway.storage.MediaStorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-C1/C2 接线护栏：下架处置的物理删除必须走发件箱，而不是同步吞异常删除。
 *
 * <p>锁住三件事：① minio 下事件行与 CAS 翻转同批产生且调用 deliverAfterCommit；
 * ② 不再同步调 {@code delete}；③ CAS 落空（已被别的路径下架）绝不重复登记删除事件。</p>
 */
@ExtendWith(MockitoExtension.class)
class ViolationDispositionStorageOutboxTest {

    private static final String MEDIA_ID = "media/42/abc.jpg";
    private static final long AUTHOR_ID = 42L;

    @Mock MediaJdbcRepository mediaRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock AccountHealthService accountHealthService;
    @Mock ModerationSignalService moderationSignalService;
    @Mock BehaviorLogService behaviorLogService;
    @Mock DispositionPolicy dispositionPolicy;
    @Mock AccountCreditService accountCreditService;
    @Mock PenaltyService penaltyService;
    @Mock MediaProperties properties;
    @Mock MediaStorageClient storageClient;
    @Mock ReviewTimelineDelivery delivery;
    @Mock OutboxService outboxService;

    private ViolationDispositionService service() {
        return new ViolationDispositionService(mediaRepository, redisTemplate, accountHealthService,
                moderationSignalService, behaviorLogService, dispositionPolicy, accountCreditService,
                penaltyService, properties, storageClient, delivery);
    }

    private void stubIntercept(int casRows) {
        when(accountHealthService.recordViolation(AUTHOR_ID, ViolationSeverity.HIGH)).thenReturn(50);
        when(mediaRepository.findPostId(MEDIA_ID, AUTHOR_ID)).thenReturn("post/42");
        when(dispositionPolicy.resolve(ViolationSource.HUMAN_REPORT, ViolationSeverity.HIGH))
                .thenReturn(Disposition.INTERCEPT);
        when(mediaRepository.updateStatusCas(MEDIA_ID, AUTHOR_ID,
                MediaStatus.APPROVED, MediaStatus.TAKEN_DOWN)).thenReturn(casRows);
    }

    @Test
    void intercept_minio_enqueuesStorageDelete_neverSyncDeletes() throws Exception {
        stubIntercept(1);
        when(properties.getStorage()).thenReturn("minio");
        when(outboxService.enqueue(eq(OutboxEventType.STORAGE_DELETE), eq(MEDIA_ID), eq(AUTHOR_ID), any()))
                .thenReturn(777L);
        ViolationDispositionService svc = service();
        ReflectionTestUtils.setField(svc, "outboxService", outboxService);

        assertEquals(1, svc.applyDisposition(MEDIA_ID, AUTHOR_ID, ViolationSource.HUMAN_REPORT,
                "SYSTEM", "高危举报", ViolationSeverity.HIGH));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).enqueue(eq(OutboxEventType.STORAGE_DELETE), eq(MEDIA_ID), eq(AUTHOR_ID),
                payload.capture());
        StorageDeletePayload p = (StorageDeletePayload) payload.getValue();
        assertEquals(MEDIA_ID, p.mediaId());
        assertEquals("minio", p.storage());
        assertNotNull(p.occurredAt(), "occurredAt 必须落在 payload 里，供运维对账删除延迟");
        verify(outboxService).deliverAfterCommit(777L);
        verify(storageClient, never()).delete(anyString());
        verify(storageClient, never()).deleteOrThrow(anyString());
    }

    @Test
    void intercept_outboxNotWired_fallsBackToLegacySyncDelete() {
        stubIntercept(1);
        when(properties.getStorage()).thenReturn("minio");

        assertEquals(1, service().applyDisposition(MEDIA_ID, AUTHOR_ID, ViolationSource.HUMAN_REPORT,
                "SYSTEM", "高危举报", ViolationSeverity.HIGH));

        verify(storageClient).delete(MEDIA_ID);
    }

    @Test
    void intercept_casLost_doesNotEnqueueStorageDelete() {
        stubIntercept(0);
        ViolationDispositionService svc = service();
        ReflectionTestUtils.setField(svc, "outboxService", outboxService);

        assertEquals(0, svc.applyDisposition(MEDIA_ID, AUTHOR_ID, ViolationSource.HUMAN_REPORT,
                "SYSTEM", "高危举报", ViolationSeverity.HIGH));

        verifyNoInteractions(outboxService);
        verify(storageClient, never()).delete(anyString());
    }

    @Test
    void intercept_nonMinioStorage_doesNotEnqueue() {
        stubIntercept(1);
        when(properties.getStorage()).thenReturn("local");
        ViolationDispositionService svc = service();
        ReflectionTestUtils.setField(svc, "outboxService", outboxService);

        assertEquals(1, svc.applyDisposition(MEDIA_ID, AUTHOR_ID, ViolationSource.HUMAN_REPORT,
                "SYSTEM", "高危举报", ViolationSeverity.HIGH));

        verifyNoInteractions(outboxService);
    }
}
