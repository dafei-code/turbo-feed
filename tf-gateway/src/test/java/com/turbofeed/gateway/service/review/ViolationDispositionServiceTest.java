package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.penalty.PenaltyService;
import com.turbofeed.gateway.service.penalty.ViolationCategory;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.health.AccountHealthService;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.signal.ModerationSignalService;
import com.turbofeed.gateway.service.behavior.BehaviorLogService;
import com.turbofeed.gateway.storage.MediaStorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-1 靶心：梯度处置分发（#310 从 MediaReviewService 拆出）。
 * 锁定「确认违规 → 健康分/信号/行为日志 + INTERCEPT 下架扣信用落处罚 / MONITOR 限公域」。
 */
@ExtendWith(MockitoExtension.class)
class ViolationDispositionServiceTest {

    @Mock MediaJdbcRepository mediaRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock AccountHealthService accountHealthService;
    @Mock ModerationSignalService moderationSignalService;
    @Mock BehaviorLogService behaviorLogService;
    @Mock DispositionPolicy dispositionPolicy;
    @Mock AccountCreditService accountCreditService;
    @Mock PenaltyService penaltyService;
    @Mock MediaProperties properties;
    @Mock MediaStorageClient storageClient;
    @Mock ReviewTimelineDelivery delivery;

    private ViolationDispositionService service() {
        return new ViolationDispositionService(mediaRepository, redisTemplate, accountHealthService,
                moderationSignalService, behaviorLogService, dispositionPolicy, accountCreditService,
                penaltyService, properties, storageClient, delivery);
    }

    @Test
    void applyDisposition_intercept_takeDownAndPenalize() {
        String mediaId = "media/42/abc.jpg";
        long authorId = 42L;
        when(accountHealthService.recordViolation(authorId, ViolationSeverity.HIGH)).thenReturn(50);
        when(mediaRepository.findPostId(mediaId, authorId)).thenReturn("post/42");
        when(dispositionPolicy.resolve(ViolationSource.HUMAN_REPORT, ViolationSeverity.HIGH)).thenReturn(Disposition.INTERCEPT);
        when(mediaRepository.updateStatusCas(mediaId, authorId, MediaStatus.APPROVED, MediaStatus.TAKEN_DOWN)).thenReturn(1);

        int rows = service().applyDisposition(mediaId, authorId, ViolationSource.HUMAN_REPORT, "SYSTEM",
                "高危举报立即下架停推", ViolationSeverity.HIGH);

        assertEquals(1, rows);
        verify(delivery).removeFromTimeline(mediaId, authorId);
        verify(accountCreditService).onViolationConfirmed(authorId);
        verify(penaltyService).recordViolation(eq(authorId), eq(ViolationCategory.OTHER), eq(ViolationSeverity.HIGH),
                eq(ViolationSource.HUMAN_REPORT), eq(mediaId), eq("SYSTEM"), anyString());
        verify(moderationSignalService).publishAccountHealth(eq(authorId), eq(50), anyString());
        verify(moderationSignalService).publishMediaDisposition(eq("post/42"), eq(Disposition.INTERCEPT),
                eq(ViolationSeverity.HIGH), eq(ViolationSource.HUMAN_REPORT));
    }

    @Test
    void applyDisposition_monitor_softLimit_noTakeDown() {
        String mediaId = "media/42/abc.jpg";
        long authorId = 42L;
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(accountHealthService.recordViolation(authorId, ViolationSeverity.LOW)).thenReturn(90);
        when(mediaRepository.findPostId(mediaId, authorId)).thenReturn("post/42");
        when(dispositionPolicy.resolve(ViolationSource.HUMAN_REPORT, ViolationSeverity.LOW)).thenReturn(Disposition.MONITOR);

        int rows = service().applyDisposition(mediaId, authorId, ViolationSource.HUMAN_REPORT, "SYSTEM",
                "软处置", ViolationSeverity.LOW);

        assertEquals(1, rows);
        // MONITOR 仍移出公域（限公域/仅自己可见），但状态保持、不扣信用、不落处罚
        verify(delivery).removeFromTimeline(mediaId, authorId);
        verify(mediaRepository, never()).updateStatusCas(anyString(), anyLong(), any(), any());
        verify(accountCreditService, never()).onViolationConfirmed(anyLong());
        verify(penaltyService, never()).recordViolation(anyLong(), any(), any(), any(), any(), any(), any());
    }
}
