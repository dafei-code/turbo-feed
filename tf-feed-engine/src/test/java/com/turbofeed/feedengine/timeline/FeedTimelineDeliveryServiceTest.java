package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.interest.TagIndexService;
import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.model.FeedTimelineEvent;
import com.turbofeed.shared.model.UnsupportedContractVersionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link FeedTimelineDeliveryService} 单测（M1 / R7：双投递通道副作用收敛）。
 *
 * <p><b>守护的契约</b>：一次投递 = 「时间线写入/摘除 + 标签索引 + 推荐流缓存失效」三件事，
 * 由<b>唯一入口</b>保证。改造前 MQ 通道漏了第三条（缓存失效），导致开 MQ 后新内容要等满 TTL 才可见。
 * 本测试把「三条副作用必须齐全」变成机器可校验的事实。</p>
 */
@ExtendWith(MockitoExtension.class)
class FeedTimelineDeliveryServiceTest {

    @Mock
    private FeedTimelineStore feedTimelineStore;
    @Mock
    private TagIndexService tagIndexService;
    @Mock
    private RecommendedFeedService recommendedFeedService;

    private FeedTimelineDeliveryService delivery() {
        return new FeedTimelineDeliveryService(feedTimelineStore, tagIndexService, recommendedFeedService);
    }

    private static FeedItemView item(String postId) {
        return new FeedItemView(postId, "m-" + postId, "http://img", List.of("http://img"),
                FeedItemView.STATUS_APPROVED, Instant.now(), "caption", "mark", List.of("tagA", "tagB"));
    }

    // ==================== 三条副作用必须齐全 ====================

    @Test
    void apply_append_triggersAllThreeSideEffects() {
        FeedItemView it = item("p1");
        delivery().apply(FeedTimelineEvent.append(it, 2));

        verify(feedTimelineStore).append(it, 2);
        verify(tagIndexService).index("p1", List.of("tagA", "tagB"));
        verify(recommendedFeedService).invalidate();
    }

    @Test
    void apply_remove_triggersAllThreeSideEffects() {
        delivery().apply(FeedTimelineEvent.remove("p1"));

        verify(feedTimelineStore).remove("p1");
        verify(tagIndexService).remove("p1");
        // 这条最容易漏：改造前的 MQ 通道就没做，导致下架内容仍被缓存继续返回
        verify(recommendedFeedService).invalidate();
    }

    @Test
    void apply_sideEffectsInOrder() {
        // 顺序不可调换：先落时间线，再维护索引，最后失效缓存
        FeedItemView it = item("p1");
        delivery().apply(FeedTimelineEvent.append(it, 1));

        InOrder order = inOrder(feedTimelineStore, tagIndexService, recommendedFeedService);
        order.verify(feedTimelineStore).append(it, 1);
        order.verify(tagIndexService).index(anyString(), any());
        order.verify(recommendedFeedService).invalidate();
    }

    @Test
    void applyStrict_append_triggersAllThreeSideEffects() {
        // strict 通道同样必须齐全——这正是此前漏掉 invalidate 的那条路径
        FeedItemView it = item("p1");
        delivery().applyStrict(FeedTimelineEvent.append(it, 3));

        verify(feedTimelineStore).appendStrict(it, 3);
        verify(tagIndexService).index("p1", List.of("tagA", "tagB"));
        verify(recommendedFeedService).invalidate();
    }

    // ==================== 通道语义差异 ====================

    @Test
    void applyStrict_usesStrictWriteEntry() {
        FeedItemView it = item("p1");
        delivery().applyStrict(FeedTimelineEvent.append(it, 1));
        verify(feedTimelineStore).appendStrict(it, 1);
        verify(feedTimelineStore, never()).append(any(FeedItemView.class), anyInt());
    }

    @Test
    void applyStrict_remove_usesStrictWriteEntry() {
        delivery().applyStrict(FeedTimelineEvent.remove("p9"));
        verify(feedTimelineStore).removeStrict("p9");
        verify(feedTimelineStore, never()).remove(anyString());
    }

    @Test
    void applyStrict_storeFailure_propagatesForRetry() {
        // MQ 重试 / DLQ 依赖异常冒泡：吞掉就等于静默丢失
        FeedItemView it = item("p1");
        doThrow(new IllegalStateException("redis down"))
                .when(feedTimelineStore).appendStrict(it, 1);

        assertThrows(IllegalStateException.class,
                () -> delivery().applyStrict(FeedTimelineEvent.append(it, 1)),
                "严格通道必须让异常冒泡，否则 RocketMQ 重试与 DLQ 永不触发");
        verify(recommendedFeedService, never()).invalidate();
    }

    @Test
    void apply_storeFailure_isSwallowed() {
        // HTTP 兜底通道是 best-effort：与主流程一致，不把引擎抖动抛给调用方
        FeedItemView it = item("p1");
        doThrow(new IllegalStateException("redis down"))
                .when(feedTimelineStore).append(it, 1);

        assertDoesNotThrow(() -> delivery().apply(FeedTimelineEvent.append(it, 1)));
    }

    // ==================== 拒绝"按 REMOVE 兜底" ====================

    @Test
    void applyStrict_unknownAction_throwsAndChangesNothing() {
        FeedTimelineEvent bad = new FeedTimelineEvent("p1", "WHATEVER", null, null, Instant.now());

        assertThrows(IllegalArgumentException.class, () -> delivery().applyStrict(bad));
        // 绝不改动任何数据：坏消息被当成下架是反方向的安全事故
        verifyNoInteractions(feedTimelineStore, tagIndexService, recommendedFeedService);
    }

    @Test
    void apply_unknownAction_doesNotThrowAndChangesNothing() {
        FeedTimelineEvent bad = new FeedTimelineEvent("p1", "WHATEVER", null, null, Instant.now());

        assertDoesNotThrow(() -> delivery().apply(bad));
        verifyNoInteractions(feedTimelineStore, tagIndexService, recommendedFeedService);
    }

    @Test
    void applyStrict_appendWithoutItem_throws() {
        FeedTimelineEvent broken = new FeedTimelineEvent("p1", FeedTimelineEvent.ACTION_APPEND, 1, null, Instant.now());
        assertThrows(IllegalArgumentException.class, () -> delivery().applyStrict(broken));
        verifyNoInteractions(feedTimelineStore);
    }

    @Test
    void applyStrict_removeWithoutKey_throws() {
        FeedTimelineEvent broken = new FeedTimelineEvent(null, FeedTimelineEvent.ACTION_REMOVE, null, null, Instant.now());
        assertThrows(IllegalArgumentException.class, () -> delivery().applyStrict(broken));
        verifyNoInteractions(feedTimelineStore);
    }

    @Test
    void apply_appendWithNullPoolLevel_defaultsToOne() {
        FeedItemView it = item("p1");
        delivery().apply(new FeedTimelineEvent("p1", FeedTimelineEvent.ACTION_APPEND, null, it, Instant.now()));
        verify(feedTimelineStore).append(it, 1);
    }

    // ==================== 契约版本协商（M2 / 4.1） ====================

    /**
     * 高版本消息在<b>严格通道</b>必须上抛 → RocketMQ 重试耗尽后进 DLQ，留痕可巡检。
     *
     * <p>最危险的反面行为是「静默按当前语义处理」：生产者升了版本、消费者没升，
     * 消息照样解析得出来（Jackson 不会因为数字大而报错），却会被按错误的字段语义写进时间线。</p>
     */
    @Test
    void applyStrict_futureSchemaVersion_throwsAndChangesNothing() {
        FeedTimelineEvent future = new FeedTimelineEvent("p1", FeedTimelineEvent.ACTION_REMOVE, null, null,
                Instant.now(), FeedTimelineEvent.SCHEMA_VERSION + 1);

        assertThrows(UnsupportedContractVersionException.class, () -> delivery().applyStrict(future));
        verifyNoInteractions(feedTimelineStore, tagIndexService, recommendedFeedService);
    }

    /** 高版本消息在<b>HTTP 兜底通道</b>只告警、不改动数据（该通道本就是 best-effort）。 */
    @Test
    void apply_futureSchemaVersion_doesNotThrowAndChangesNothing() {
        FeedTimelineEvent future = new FeedTimelineEvent("p1", FeedTimelineEvent.ACTION_APPEND, 1, item("p1"),
                Instant.now(), FeedTimelineEvent.SCHEMA_VERSION + 1);

        assertDoesNotThrow(() -> delivery().apply(future));
        verifyNoInteractions(feedTimelineStore, tagIndexService, recommendedFeedService);
    }

    /**
     * 存量消息（本字段引入前产生，反序列化为 {@code null}）必须被正常消费——
     * MQ 里未被消费的旧消息与已物化的成员串都补不回版本号，把它们拒掉等于上线即事故。
     */
    @Test
    void apply_unversionedLegacyEvent_isTolerated() {
        FeedTimelineEvent legacy = new FeedTimelineEvent("p1", FeedTimelineEvent.ACTION_REMOVE, null, null,
                Instant.now(), null);

        delivery().apply(legacy);

        verify(feedTimelineStore).remove("p1");
        verify(tagIndexService).remove("p1");
        verify(recommendedFeedService).invalidate();
    }

    /** 信封版本一致但内嵌条目版本更高：同样必须拒绝（条目会被持久化进成员串，错得更久）。 */
    @Test
    void applyStrict_futureItemSchemaVersion_throwsAndChangesNothing() {
        FeedItemView futureItem = new FeedItemView("p1", "m-p1", "http://img", null,
                FeedItemView.STATUS_APPROVED, Instant.now(), null, null, null,
                FeedItemView.SCHEMA_VERSION + 1);
        // 信封用便捷构造器（当前版本），只有内嵌条目是高版本
        FeedTimelineEvent event = new FeedTimelineEvent("p1", FeedTimelineEvent.ACTION_APPEND, 1, futureItem,
                Instant.now(), FeedTimelineEvent.SCHEMA_VERSION);

        assertThrows(UnsupportedContractVersionException.class, () -> delivery().applyStrict(event));
        verifyNoInteractions(feedTimelineStore, tagIndexService, recommendedFeedService);
    }
}
