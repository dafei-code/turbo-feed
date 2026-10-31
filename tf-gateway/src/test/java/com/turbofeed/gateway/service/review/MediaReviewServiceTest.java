package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.AppealRepository;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ReportRepository;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.service.event.outbox.OutboxService;
import com.turbofeed.gateway.service.penalty.PenaltyService;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.review.credit.ReporterCreditService;
import com.turbofeed.gateway.service.behavior.BehaviorLogService;
import com.turbofeed.gateway.service.review.ModerationPolicy;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-1 靶心①：审核 CAS 状态机（{@link MediaReviewService#review}）。
 *
 * <p>纯 Mockito 单测，不启 Spring、不需要 Docker——直接构造 service 并验证
 * 「PENDING→APPROVED/REJECTED 终态流转 / CAS 落空幂等 / 终态幂等 / 未找到抛错」。
 * 这是 changelog 反复踩坑（InnoDB 死锁、TOCTOU）的核心正确性逻辑，必须有回归保护。</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaReviewServiceTest {

    @Mock MediaJdbcRepository mediaRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ContentModerationRouter contentModeration;
    @Mock ReviewProperties reviewProperties;
    @Mock AccountCreditService accountCreditService;
    @Mock ReportRepository reportRepository;
    @Mock AppealRepository appealRepository;
    @Mock ReviewTaskRepository reviewTaskRepository;
    @Mock OutboxService outboxService;
    @Mock PenaltyService penaltyService;
    @Mock ReporterCreditService reporterCreditService;
    @Mock ReviewWaterLevelService reviewWaterLevelService;
    @Mock BehaviorLogService behaviorLogService;
    @Mock ModerationPolicy moderationPolicy;
    @Mock ReviewTimelineDelivery delivery;
    @Mock ViolationDispositionService disposition;
    @Mock ReviewEscalationService escalation;
    @Mock NotificationGenerator notificationGenerator;

    private MediaReviewService service() {
        return new MediaReviewService(mediaRepository, redisTemplate, contentModeration, reviewProperties,
                accountCreditService, reportRepository, appealRepository, reviewTaskRepository, outboxService,
                penaltyService, reporterCreditService, reviewWaterLevelService, behaviorLogService, moderationPolicy,
                delivery, disposition, escalation, notificationGenerator);
    }

    @Test
    void review_pendingToApproved_transitionsOnce() {
        String mediaId = "media/42/abc.jpg";
        long userId = 42L;
        when(mediaRepository.getStatus(mediaId, userId)).thenReturn(MediaStatus.PENDING);
        when(mediaRepository.updateStatusCas(mediaId, userId, MediaStatus.PENDING, MediaStatus.APPROVED))
                .thenReturn(1);

        MediaReviewService.ReviewOutcome o = service().review(mediaId, userId, true);

        assertEquals(MediaStatus.APPROVED, o.status());
        assertTrue(o.transitioned());
        verify(mediaRepository).updateStatusCas(mediaId, userId, MediaStatus.PENDING, MediaStatus.APPROVED);
    }

    @Test
    void review_casLost_returnsActualWithoutDoubleDeliver() {
        String mediaId = "media/42/abc.jpg";
        long userId = 42L;
        when(mediaRepository.getStatus(mediaId, userId)).thenReturn(MediaStatus.PENDING, MediaStatus.APPROVED);
        when(mediaRepository.updateStatusCas(mediaId, userId, MediaStatus.PENDING, MediaStatus.APPROVED))
                .thenReturn(0); // CAS 落空：并发路径已流转

        MediaReviewService.ReviewOutcome o = service().review(mediaId, userId, true);

        assertEquals(MediaStatus.APPROVED, o.status());
        assertFalse(o.transitioned(), "CAS 落空不得标记为已流转，避免重复投递时间线");
        verify(mediaRepository, times(1)).updateStatusCas(anyString(), anyLong(), any(), any());
    }

    @Test
    void review_alreadyTerminal_isIdempotent() {
        String mediaId = "media/42/abc.jpg";
        long userId = 42L;
        when(mediaRepository.getStatus(mediaId, userId)).thenReturn(MediaStatus.REJECTED);

        MediaReviewService.ReviewOutcome o = service().review(mediaId, userId, true);

        assertEquals(MediaStatus.REJECTED, o.status());
        assertFalse(o.transitioned());
        verify(mediaRepository, never()).updateStatusCas(anyString(), anyLong(), any(), any());
    }

    @Test
    void review_notFound_throws() {
        String mediaId = "media/42/ghost.jpg";
        long userId = 42L;
        when(mediaRepository.getStatus(mediaId, userId)).thenReturn(null);

        assertThrows(BizException.class, () -> service().review(mediaId, userId, true));
    }
}
