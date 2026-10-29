package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.ReportRepository;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.credit.ReporterCreditService;
import com.turbofeed.gateway.service.review.ContentModerationRouter;
import com.turbofeed.gateway.service.review.ModerationVerdict;
import com.turbofeed.gateway.service.review.ReviewProperties;
import com.turbofeed.gateway.service.review.ReviewWaterLevelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-1 靶心：晋级/举报升级（#310 从 MediaReviewService 拆出）。
 * 锁定「越火审得越严」「流量池晋级必过人审」「同类举报累计→人工复审」三条触发链路。
 */
@ExtendWith(MockitoExtension.class)
class ReviewEscalationServiceTest {

    @Mock MediaJdbcRepository mediaRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ContentModerationRouter contentModeration;
    @Mock ReviewProperties reviewProperties;
    @Mock ReportRepository reportRepository;
    @Mock ReporterCreditService reporterCreditService;
    @Mock ReviewWaterLevelService reviewWaterLevelService;
    @Mock ReviewTaskRepository reviewTaskRepository;
    @Mock ViolationDispositionService disposition;

    private ReviewEscalationService service() {
        return new ReviewEscalationService(mediaRepository, redisTemplate, contentModeration, reviewProperties,
                reportRepository, reporterCreditService, reviewWaterLevelService, reviewTaskRepository, disposition);
    }

    @Test
    void onInteraction_heatCrossesThreshold_createsHeatTask() {
        String postId = "post/42";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("tf:media:heat:" + postId)).thenReturn(500L);
        when(reviewProperties.getHeatReReviewThreshold()).thenReturn(500);
        MediaJdbcRepository.PostRepresentative rep = mock(MediaJdbcRepository.PostRepresentative.class);
        when(rep.status()).thenReturn(MediaStatus.APPROVED);
        when(rep.mediaId()).thenReturn("media/42/abc.jpg");
        when(rep.authorId()).thenReturn(42L);
        when(mediaRepository.findRepresentativeByPostId(postId)).thenReturn(rep);
        when(reviewTaskRepository.existsOpenForMedia("media/42/abc.jpg")).thenReturn(false);

        service().onInteraction(postId, "LIKE");

        verify(reviewTaskRepository).insert(eq("media/42/abc.jpg"), eq(42L),
                eq(ReviewTask.TYPE_HEAT_ACCUMULATED), eq(500));
    }

    @Test
    void onInteraction_nonPositive_noHeatAndNoTask() {
        service().onInteraction("post/42", "VIEW");
        verify(valueOps, never()).increment(anyString());
        verify(reviewTaskRepository, never()).insert(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void onPoolPromoted_buildsPoolReviewTaskAndTightensThreshold() {
        String postId = "post/42";
        int to = 3;
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaJdbcRepository.PostRepresentative rep = mock(MediaJdbcRepository.PostRepresentative.class);
        when(rep.status()).thenReturn(MediaStatus.APPROVED);
        when(rep.mediaId()).thenReturn("media/42/abc.jpg");
        when(rep.authorId()).thenReturn(42L);
        when(rep.url()).thenReturn("cover");
        when(mediaRepository.findRepresentativeByPostId(postId)).thenReturn(rep);
        ModerationVerdict verdict = mock(ModerationVerdict.class);
        when(verdict.decision()).thenReturn(MediaStatus.APPROVED);
        when(contentModeration.verdict(anyString(), anyLong(), anyString())).thenReturn(verdict);
        when(mediaRepository.getStatus("media/42/abc.jpg", 42L)).thenReturn(MediaStatus.APPROVED);
        when(reviewTaskRepository.existsOpenForMedia("media/42/abc.jpg")).thenReturn(false);
        when(reviewProperties.getPoolHeatThresholdL3()).thenReturn(200);

        service().onPoolPromoted(postId, 2, to);

        verify(reviewTaskRepository).insert(eq("media/42/abc.jpg"), eq(42L),
                eq(ReviewTask.TYPE_POOL_PROMOTED), eq(to));
        verify(valueOps).set(eq("tf:media:heat-threshold:" + postId), eq("200"));
        verify(disposition, never()).applyDisposition(anyString(), anyLong(), any(), any(), any(), any());
    }
}
