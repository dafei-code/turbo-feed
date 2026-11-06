package com.turbofeed.gateway.service.signal;

import com.turbofeed.gateway.service.moderation.ContentScene;
import com.turbofeed.gateway.service.moderation.ContentSecurityProperties;
import com.turbofeed.gateway.service.moderation.ContentVerdict;
import com.turbofeed.gateway.service.moderation.ModerationAction;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import com.turbofeed.gateway.service.review.Disposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModerationDownstreamRouter} 策略单测：内容级 REVIEW/LIMIT → 引擎降权信号（MONITOR）。
 *
 * <p>把策略从 {@code MediaUploadService}（23 参构造、主链极重）中抽出来，正是为了能用 2 个 mock
 * 锁死下列不变量——这也是本类存在的核心原因之一（见类注释）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModerationDownstreamRouterTest {

    @Mock
    ContentSecurityProperties properties;
    @Mock
    ModerationSignalService signalService;
    @InjectMocks
    ModerationDownstreamRouter router;

    private static ContentVerdict v(ModerationAction action) {
        return new ContentVerdict(action, ContentScene.CAPTION, "w", "c", false, 0, 1);
    }

    @Test
    void review_publishesMonitorWithMidSeverity_whenSwitchOn() {
        when(properties.isReviewPublishSignal()).thenReturn(true);
        when(properties.getDefaultReviewSeverity()).thenReturn(ViolationSeverity.MID);

        router.routeContentVerdict("post/1", v(ModerationAction.REVIEW));

        verify(signalService, times(1)).publishMediaDisposition(
                eq("post/1"), eq(Disposition.MONITOR), eq(ViolationSeverity.MID), eq(ViolationSource.MACHINE));
    }

    @Test
    void limit_publishesMonitorWithLowSeverity_whenSwitchOn() {
        when(properties.isLimitPublishSignal()).thenReturn(true);
        when(properties.getDefaultLimitSeverity()).thenReturn(ViolationSeverity.LOW);

        router.routeContentVerdict("post/1", v(ModerationAction.LIMIT));

        verify(signalService, times(1)).publishMediaDisposition(
                eq("post/1"), eq(Disposition.MONITOR), eq(ViolationSeverity.LOW), eq(ViolationSource.MACHINE));
    }

    @Test
    void pass_doesNotPublish() {
        router.routeContentVerdict("post/1", v(ModerationAction.PASS));
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void block_doesNotPublish() {
        router.routeContentVerdict("post/1", v(ModerationAction.BLOCK));
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void reviewSwitchOff_doesNotPublish() {
        when(properties.isReviewPublishSignal()).thenReturn(false);
        router.routeContentVerdict("post/1", v(ModerationAction.REVIEW));
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void limitSwitchOff_doesNotPublish() {
        when(properties.isLimitPublishSignal()).thenReturn(false);
        router.routeContentVerdict("post/1", v(ModerationAction.LIMIT));
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void blankPostId_doesNotPublish_evenForReview() {
        // 路由先判 postId 为空直接返回，不会读取 reviewPublishSignal 开关（避免多桩告警）
        router.routeContentVerdict("   ", v(ModerationAction.REVIEW));
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void nullVerdict_doesNotPublish() {
        router.routeContentVerdict("post/1", null);
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }
}
