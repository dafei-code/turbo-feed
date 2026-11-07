package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
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

    // ---- ADR-0014：DEMOTE 防御分支与解除闭环 ----

    @Test
    void applyDisposition_demote_onlyWritesSignal_neverPenalizes() {
        String mediaId = "media/42/abc.jpg";
        long authorId = 42L;
        when(mediaRepository.findPostId(mediaId, authorId)).thenReturn("post/42");
        when(dispositionPolicy.resolve(ViolationSource.HUMAN_REPORT, ViolationSeverity.LOW))
                .thenReturn(Disposition.DEMOTE);

        int rows = service().applyDisposition(mediaId, authorId, ViolationSource.HUMAN_REPORT, "SYSTEM",
                "配置矩阵显式配 DEMOTE", ViolationSeverity.LOW);

        assertEquals(1, rows);
        verify(moderationSignalService).publishMediaDisposition(eq("post/42"), eq(Disposition.DEMOTE),
                eq(ViolationSeverity.LOW), eq(ViolationSource.HUMAN_REPORT));
        // 内容级轻降权不是「确认违规」：若漏了这个分支就会误走 takeDownAndPenalize 直接下架
        verify(accountHealthService, never()).recordViolation(anyLong(), any());
        verify(moderationSignalService, never()).publishAccountHealth(anyLong(), anyInt(), anyString());
        verify(delivery, never()).removeFromTimeline(anyString(), anyLong());
        verify(mediaRepository, never()).updateStatusCas(anyString(), anyLong(), any(), any());
        verify(accountCreditService, never()).onViolationConfirmed(anyLong());
        verify(penaltyService, never()).recordViolation(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void clearContentSignal_resolvesPostIdAndClears() {
        when(mediaRepository.findPostId("media/42/abc.jpg", 42L)).thenReturn("post/42");

        service().clearContentSignal("media/42/abc.jpg", 42L);

        verify(moderationSignalService).clearMediaDisposition("post/42");
    }

    @Test
    void clearContentSignal_blankPostId_doesNotClear() {
        // 历史单图帖 post_id='' 无法形成稳定信号键，宁可不解除也不误删前缀键
        when(mediaRepository.findPostId("media/42/abc.jpg", 42L)).thenReturn("");

        service().clearContentSignal("media/42/abc.jpg", 42L);

        verify(moderationSignalService, never()).clearMediaDisposition(any());
    }

    // ---- ADR-0015：判无违规恢复公域可见性（softLimit 摘除 → 重投时间线） ----

    private static final String MEDIA_ID = "media/42/abc.jpg";
    private static final long AUTHOR_ID = 42L;
    private static final String POST_ID = "post/42/uuid";
    private static final String MARKER_KEY = "tf:media:disposition:" + POST_ID;

    private static MediaItem approvedPost() {
        return new MediaItem(POST_ID, MEDIA_ID, "https://oss.turbofeed.com/media/42/abc.jpg",
                MediaStatus.APPROVED, Instant.now(), "描述", "[]");
    }

    @Test
    void restoreAfterNoViolation_takenDown_neverTouchesSignalOrTimeline() {
        when(mediaRepository.getStatus(MEDIA_ID, AUTHOR_ID)).thenReturn(MediaStatus.TAKEN_DOWN);

        assertFalse(service().restoreAfterNoViolation(MEDIA_ID, AUTHOR_ID, ReviewTask.TYPE_CONTENT_REVIEW));

        // 下架结论不能被一次「判无违规」推翻（那是申诉的职责）：连 INTERCEPT 信号都不清
        verify(moderationSignalService, never()).clearMediaDisposition(any());
        verify(redisTemplate, never()).opsForValue();
        verify(delivery, never()).publishAppend(anyString(), anyLong(), any(), anyInt());
    }

    @Test
    void restoreAfterNoViolation_contentReviewWithoutMarker_clearsSignalOnly() {
        when(mediaRepository.getStatus(MEDIA_ID, AUTHOR_ID)).thenReturn(MediaStatus.APPROVED);
        when(mediaRepository.findPostId(MEDIA_ID, AUTHOR_ID)).thenReturn(POST_ID);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // valueOps.get 默认 null：没被 softLimit 摘过时间线，清 MONITOR 隔离信号即可

        assertFalse(service().restoreAfterNoViolation(MEDIA_ID, AUTHOR_ID, ReviewTask.TYPE_CONTENT_REVIEW));

        verify(moderationSignalService).clearMediaDisposition(POST_ID);
        verify(delivery, never()).publishAppend(anyString(), anyLong(), any(), anyInt());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void restoreAfterNoViolation_otherTaskWithoutMarker_doesNotClearSignal() {
        when(mediaRepository.getStatus(MEDIA_ID, AUTHOR_ID)).thenReturn(MediaStatus.APPROVED);
        when(mediaRepository.findPostId(MEDIA_ID, AUTHOR_ID)).thenReturn(POST_ID);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        assertFalse(service().restoreAfterNoViolation(MEDIA_ID, AUTHOR_ID,
                ReviewTask.TYPE_REPORT_ACCUMULATED));

        // ADR-0014 原语义：举报/热度/晋级任务判无违规，不得误清尚未判决的内容审核隔离或 INTERCEPT
        verify(moderationSignalService, never()).clearMediaDisposition(any());
        verify(delivery, never()).publishAppend(anyString(), anyLong(), any(), anyInt());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void restoreAfterNoViolation_softLimited_republishesThenDeletesMarker() {
        MediaItem post = approvedPost();
        when(mediaRepository.getStatus(MEDIA_ID, AUTHOR_ID)).thenReturn(MediaStatus.APPROVED);
        when(mediaRepository.findPostId(MEDIA_ID, AUTHOR_ID)).thenReturn(POST_ID);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(MARKER_KEY)).thenReturn(Disposition.MONITOR.name());
        when(mediaRepository.findMedia(MEDIA_ID, AUTHOR_ID)).thenReturn(post);
        when(delivery.asTimelinePost(post, AUTHOR_ID)).thenReturn(post);
        when(accountCreditService.ensure(AUTHOR_ID)).thenReturn(CreditLevel.L1);

        assertTrue(service().restoreAfterNoViolation(MEDIA_ID, AUTHOR_ID,
                ReviewTask.TYPE_REPORT_ACCUMULATED));

        verify(moderationSignalService).clearMediaDisposition(POST_ID);
        // 标记必须最后删：Redis 删除不随 DB 事务回滚，投递失败时须保留标记以便重试
        InOrder order = inOrder(delivery, redisTemplate);
        order.verify(delivery).publishAppend(MEDIA_ID, AUTHOR_ID, post, CreditLevel.L1.poolLevel());
        order.verify(redisTemplate).delete(MARKER_KEY);
    }

    @Test
    void restoreAfterNoViolation_postMissing_keepsMarkerForRetry() {
        when(mediaRepository.getStatus(MEDIA_ID, AUTHOR_ID)).thenReturn(MediaStatus.APPROVED);
        when(mediaRepository.findPostId(MEDIA_ID, AUTHOR_ID)).thenReturn(POST_ID);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(MARKER_KEY)).thenReturn(Disposition.MONITOR.name());
        // findMedia / asTimelinePost 默认返回 null：查不到帖子视图

        assertFalse(service().restoreAfterNoViolation(MEDIA_ID, AUTHOR_ID,
                ReviewTask.TYPE_REPORT_ACCUMULATED));

        verify(redisTemplate, never()).delete(anyString());
        verify(delivery, never()).publishAppend(anyString(), anyLong(), any(), anyInt());
    }

    @Test
    void restoreAfterNoViolation_blankPostId_usesMediaIdAsMarkerKey() {
        MediaItem post = approvedPost();
        String markerKey = "tf:media:disposition:" + MEDIA_ID;
        when(mediaRepository.getStatus(MEDIA_ID, AUTHOR_ID)).thenReturn(MediaStatus.APPROVED);
        when(mediaRepository.findPostId(MEDIA_ID, AUTHOR_ID)).thenReturn("");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(markerKey)).thenReturn(Disposition.MONITOR.name());
        when(mediaRepository.findMedia(MEDIA_ID, AUTHOR_ID)).thenReturn(post);
        when(delivery.asTimelinePost(post, AUTHOR_ID)).thenReturn(post);
        when(accountCreditService.ensure(AUTHOR_ID)).thenReturn(CreditLevel.L2);

        assertTrue(service().restoreAfterNoViolation(MEDIA_ID, AUTHOR_ID,
                ReviewTask.TYPE_REPORT_ACCUMULATED));

        // post_id 为空时 softLimit 用 mediaId 作标记键；信号键不稳定则不解除（避免误删前缀键）
        verify(moderationSignalService, never()).clearMediaDisposition(any());
        verify(redisTemplate).delete(markerKey);
        verify(delivery).publishAppend(MEDIA_ID, AUTHOR_ID, post, CreditLevel.L2.poolLevel());
    }
}
