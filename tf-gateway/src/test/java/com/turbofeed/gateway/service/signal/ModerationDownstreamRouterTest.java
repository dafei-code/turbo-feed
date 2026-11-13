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
 * {@link ModerationDownstreamRouter} 策略单测（ADR-0014）。
 *
 * <p>把策略从上传门面中抽出来，正是为了能用 2 个 mock 锁死下列不变量：</p>
 * <ul>
 *   <li>上传：REVIEW → MONITOR（公域隔离）、LIMIT → DEMOTE（轻降权）、PASS/BLOCK 不发；</li>
 *   <li>改描述：只升级不降级，PASS 只条件解除 DEMOTE，绝不冲掉 MONITOR/INTERCEPT；</li>
 *   <li>开关可关、postId 为空不发、任何裁定都不会误发 MONITOR 给 LIMIT。</li>
 * </ul>
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
        // 第 8 参 hitId：本测试只验证信号路由，不涉及命中审计行回链，传 null 即可
        return new ContentVerdict(action, ContentScene.CAPTION, "w", "c", false, 0, 1, null);
    }

    private static ModerationSignalView.DispositionView signal(String action) {
        return new ModerationSignalView.DispositionView(action, "MID", "MACHINE", 1L);
    }

    // ==================== 上传路径：routeContentVerdict ====================

    @Test
    void review_publishesMonitorWithMidSeverity_whenSwitchOn() {
        when(properties.isReviewPublishSignal()).thenReturn(true);
        when(properties.getDefaultReviewSeverity()).thenReturn(ViolationSeverity.MID);

        router.routeContentVerdict("post/1", v(ModerationAction.REVIEW));

        verify(signalService, times(1)).publishMediaDisposition(
                eq("post/1"), eq(Disposition.MONITOR), eq(ViolationSeverity.MID), eq(ViolationSource.MACHINE));
    }

    @Test
    void limit_publishesDemoteWithLowSeverity_whenSwitchOn() {
        when(properties.isLimitPublishSignal()).thenReturn(true);
        when(properties.getDefaultLimitSeverity()).thenReturn(ViolationSeverity.LOW);

        router.routeContentVerdict("post/1", v(ModerationAction.LIMIT));

        verify(signalService, times(1)).publishMediaDisposition(
                eq("post/1"), eq(Disposition.DEMOTE), eq(ViolationSeverity.LOW), eq(ViolationSource.MACHINE));
    }

    @Test
    void limit_neverPublishesMonitor_regressionGuardForOverDisposition() {
        // ADR-0013 把 LIMIT 也发 MONITOR，等于把整帖从公域/搜索硬剔除；这条用例锁死该回归
        when(properties.isLimitPublishSignal()).thenReturn(true);
        when(properties.getDefaultLimitSeverity()).thenReturn(ViolationSeverity.LOW);

        router.routeContentVerdict("post/1", v(ModerationAction.LIMIT));

        verify(signalService, never()).publishMediaDisposition(
                any(), eq(Disposition.MONITOR), any(), any());
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

    // ==================== 改描述路径：routeCaptionUpdate（只升级不降级） ====================

    @Test
    void captionPass_onlyClearsDemotion_neverClearsMonitorOrIntercept() {
        router.routeCaptionUpdate("post/1", v(ModerationAction.PASS));

        // 只调用条件解除（内部只在 action=DEMOTE 时删键），绝不整键无条件删、也绝不写新信号
        verify(signalService, times(1)).clearDemotionIfPresent("post/1");
        verify(signalService, never()).clearMediaDisposition(any());
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void captionLimit_noExistingSignal_publishesDemote() {
        when(properties.isLimitPublishSignal()).thenReturn(true);
        when(properties.getDefaultLimitSeverity()).thenReturn(ViolationSeverity.LOW);

        router.routeCaptionUpdate("post/1", v(ModerationAction.LIMIT));

        verify(signalService, times(1)).publishMediaDisposition(
                eq("post/1"), eq(Disposition.DEMOTE), eq(ViolationSeverity.LOW), eq(ViolationSource.MACHINE));
    }

    @Test
    void captionLimit_existingMonitor_doesNotDowngrade() {
        when(properties.isLimitPublishSignal()).thenReturn(true);
        when(signalService.getMediaDisposition("post/1")).thenReturn(signal("MONITOR"));

        router.routeCaptionUpdate("post/1", v(ModerationAction.LIMIT));

        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void captionLimit_existingIntercept_doesNotDowngrade() {
        when(properties.isLimitPublishSignal()).thenReturn(true);
        when(signalService.getMediaDisposition("post/1")).thenReturn(signal("INTERCEPT"));

        router.routeCaptionUpdate("post/1", v(ModerationAction.LIMIT));

        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void captionReview_existingDemote_upgradesToMonitor() {
        when(properties.isReviewPublishSignal()).thenReturn(true);
        when(properties.getDefaultReviewSeverity()).thenReturn(ViolationSeverity.MID);
        when(signalService.getMediaDisposition("post/1")).thenReturn(signal("DEMOTE"));

        router.routeCaptionUpdate("post/1", v(ModerationAction.REVIEW));

        verify(signalService, times(1)).publishMediaDisposition(
                eq("post/1"), eq(Disposition.MONITOR), eq(ViolationSeverity.MID), eq(ViolationSource.MACHINE));
    }

    @Test
    void captionReview_existingIntercept_keepsIntercept() {
        when(properties.isReviewPublishSignal()).thenReturn(true);
        when(signalService.getMediaDisposition("post/1")).thenReturn(signal("INTERCEPT"));

        router.routeCaptionUpdate("post/1", v(ModerationAction.REVIEW));

        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void captionLimitSwitchOff_doesNotPublish() {
        when(properties.isLimitPublishSignal()).thenReturn(false);

        router.routeCaptionUpdate("post/1", v(ModerationAction.LIMIT));

        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void captionBlankPostId_doesNothing() {
        router.routeCaptionUpdate("  ", v(ModerationAction.PASS));

        verify(signalService, never()).clearDemotionIfPresent(any());
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }

    @Test
    void captionBlock_doesNothing() {
        router.routeCaptionUpdate("post/1", v(ModerationAction.BLOCK));

        verify(signalService, never()).clearDemotionIfPresent(any());
        verify(signalService, never()).publishMediaDisposition(any(), any(), any(), any());
    }
}
