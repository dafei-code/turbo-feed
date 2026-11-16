package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.interest.TagIndexService;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-D3 接线护栏：墓碑判定必须落在<b>引擎侧双通道唯一入口</b>
 * {@link FeedTimelineDeliveryService#deliver}，且语义是「静默拒绝」而非「抛异常重试」。
 *
 * <p>ADR 验收场景：APPEND 判死 → 同帖 REMOVE 成功 → Sweeper 重投 APPEND。
 * 这里把最后一跳直接喂给 delivery，验证内容不出现、指标由组件计数、事件正常标 SENT/ACK。</p>
 */
@ExtendWith(MockitoExtension.class)
class FeedTimelineTombstoneDeliveryTest {

    private static final String KEY = "post/1/x";
    private static final Instant APPEND_AT = Instant.parse("2026-11-16T08:00:00Z");
    private static final Instant REMOVE_AT = Instant.parse("2026-11-16T09:30:00Z");

    @Mock FeedTimelineStore feedTimelineStore;
    @Mock TagIndexService tagIndexService;
    @Mock RecommendedFeedService recommendedFeedService;
    @Mock TimelineTombstoneStore tombstones;

    private FeedTimelineDeliveryService delivery() {
        return new FeedTimelineDeliveryService(feedTimelineStore, tagIndexService, recommendedFeedService);
    }

    /** 可选字段注入，模拟 Spring 装配；不注入时等价于改造前行为（另有断言）。 */
    private FeedTimelineDeliveryService wired() {
        FeedTimelineDeliveryService svc = delivery();
        ReflectionTestUtils.setField(svc, "tombstones", tombstones);
        return svc;
    }

    private static FeedItemView item() {
        return new FeedItemView(KEY, "m-1", "http://img", List.of("http://img"),
                FeedItemView.STATUS_APPROVED, Instant.now(), "caption", "mark", List.of("tagA"));
    }

    private static FeedTimelineEvent appendAt(Instant at) {
        return FeedTimelineEvent.append(item(), 2).withOccurredAt(at);
    }

    private static FeedTimelineEvent removeAt(Instant at) {
        return FeedTimelineEvent.remove(KEY).withOccurredAt(at);
    }

    // ==================== 核心验收：陈旧 APPEND 静默拒绝 ====================

    @Test
    void staleAppend_isRejectedWithoutAnySideEffect_andDoesNotThrow() {
        when(tombstones.isStaleAppend(KEY, APPEND_AT)).thenReturn(true);
        FeedTimelineDeliveryService svc = wired();

        // strict 通道也必须正常返回：抛异常会触发 MQ 重试 / 发件箱 DEAD，
        // 而「产生于下架之前」重试一万次也不会变，只会把死信越堆越多。
        assertDoesNotThrow(() -> svc.applyStrict(appendAt(APPEND_AT)),
                "拒绝必须静默成功，事件才会在发件箱标 SENT / 在 MQ 被 ACK");

        verify(feedTimelineStore, never()).appendStrict(any(), anyInt());
        verify(feedTimelineStore, never()).append(any(), anyInt());
        verifyNoInteractions(tagIndexService, recommendedFeedService);
    }

    @Test
    void staleAppend_httpFallbackChannel_alsoRejectedWithoutSideEffect() {
        when(tombstones.isStaleAppend(KEY, APPEND_AT)).thenReturn(true);

        assertDoesNotThrow(() -> wired().apply(appendAt(APPEND_AT)));

        verify(feedTimelineStore, never()).append(any(), anyInt());
        verify(tagIndexService, never()).index(anyString(), any());
        verify(recommendedFeedService, never()).invalidate();
    }

    @Test
    void freshAppend_notBlocked_runsAllThreeSideEffects() {
        when(tombstones.isStaleAppend(KEY, APPEND_AT)).thenReturn(false);
        FeedItemView it = item();

        wired().applyStrict(FeedTimelineEvent.append(it, 2).withOccurredAt(APPEND_AT));

        verify(feedTimelineStore).appendStrict(it, 2);
        verify(tagIndexService).index(KEY, List.of("tagA"));
        verify(recommendedFeedService).invalidate();
    }

    // ==================== REMOVE 登记墓碑 ====================

    @Test
    void remove_writesTombstoneAfterSuccessfulDetach() {
        wired().applyStrict(removeAt(REMOVE_AT));

        InOrder order = inOrder(feedTimelineStore, tombstones, tagIndexService, recommendedFeedService);
        order.verify(feedTimelineStore).removeStrict(KEY);
        order.verify(tombstones).markRemoved(KEY, REMOVE_AT);
        order.verify(tagIndexService).remove(KEY);
        order.verify(recommendedFeedService).invalidate();
    }

    @Test
    void remove_failedDetach_doesNotWriteTombstone() {
        // 严格通道下 removeStrict 抛异常：内容其实还在流里，此时写墓碑会误拦后续合法 APPEND
        doThrow(new IllegalStateException("redis down")).when(feedTimelineStore).removeStrict(KEY);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> wired().applyStrict(removeAt(REMOVE_AT)));

        verify(tombstones, never()).markRemoved(anyString(), any());
        verify(tagIndexService, never()).remove(anyString());
    }

    @Test
    void remove_legacyEvent_passesNullOccurredAt_soStoreSkipsWriting() {
        // 存量事件行没有 occurredAt：透传 null，组件内部不写墓碑，行为零回归
        wired().apply(FeedTimelineEvent.remove(KEY).withOccurredAt(null));
        verify(tombstones).markRemoved(KEY, null);
        verify(feedTimelineStore).remove(KEY);
    }

    // ==================== 组件缺失 / 存量事件零回归 ====================

    @Test
    void tombstoneBeanAbsent_behavesExactlyAsBefore() {
        // 裁剪部署或单测直接 new 三参构造器：可选字段为 null，判定整体跳过
        FeedItemView it = item();
        delivery().apply(FeedTimelineEvent.append(it, 2));

        verify(feedTimelineStore).append(it, 2);
        verify(tagIndexService).index(KEY, List.of("tagA"));
        verify(recommendedFeedService).invalidate();
    }

    @Test
    void appendWithoutItem_stillRejectedBeforeTombstoneCheck() {
        // 坏消息防御必须在墓碑之前：不能因为「查墓碑」把参数校验绕过去
        FeedTimelineEvent broken = new FeedTimelineEvent(KEY, FeedTimelineEvent.ACTION_APPEND,
                2, null, APPEND_AT);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> wired().applyStrict(broken));
        verifyNoInteractions(tombstones, feedTimelineStore, tagIndexService, recommendedFeedService);
    }

    @Test
    void unknownAction_neverReachesTombstone() {
        FeedTimelineEvent bad = new FeedTimelineEvent(KEY, "WHATEVER", null, null, APPEND_AT);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> wired().applyStrict(bad));
        verifyNoInteractions(tombstones, feedTimelineStore, tagIndexService, recommendedFeedService);
    }
}
