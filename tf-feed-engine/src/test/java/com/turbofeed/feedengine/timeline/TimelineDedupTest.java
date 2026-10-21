package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TimelineDedup} 隔离单测（G6 整页去重核心机制，从 {@code FeedTimelineStore#readPage} 抽取）。
 *
 * <p>纯逻辑：按 {@link FeedItemView#timelineKey()}（=postId，缺省回退 mediaId）去重，
 * 与审核拦截正交（拦截不占去重名额，由调用方 {@code ||} 组合负责，本类只管重复）。无 Redis 依赖。</p>
 */
class TimelineDedupTest {

    private static FeedItemView item(String postId) {
        return new FeedItemView(postId, null, null, null, "APPROVED", null, null, null, null);
    }

    @Test
    void accept_firstOccurrence_true_thenDuplicate_false() {
        TimelineDedup d = new TimelineDedup();
        assertTrue(d.accept(item("p1")));
        assertFalse(d.accept(item("p1"))); // 同 timelineKey 重复 → false
    }

    @Test
    void accept_distinctKeys_allTrue() {
        TimelineDedup d = new TimelineDedup();
        assertTrue(d.accept(item("p1")));
        assertTrue(d.accept(item("p2")));
        assertTrue(d.accept(item("p3")));
        assertEquals(3, d.distinctCount());
    }

    @Test
    void accept_mixedSequence_marksOnlyFirstPerKey() {
        TimelineDedup d = new TimelineDedup();
        // p1,p2,p1,p3,p2 → true,true,false,true,false
        assertTrue(d.accept(item("p1")));
        assertTrue(d.accept(item("p2")));
        assertFalse(d.accept(item("p1")));
        assertTrue(d.accept(item("p3")));
        assertFalse(d.accept(item("p2")));
        assertEquals(3, d.distinctCount());
    }

    @Test
    void accept_dedupByTimelineKey_postIdTakesPrecedence() {
        // timelineKey 优先用 postId；两条 mediaId 相同但 postId 不同的内容视为不同（不误杀）
        TimelineDedup d = new TimelineDedup();
        FeedItemView byPost = new FeedItemView("post-1", "media-x", null, null, "APPROVED", null, null, null, null);
        FeedItemView byMedia = new FeedItemView(null, "media-x", null, null, "APPROVED", null, null, null, null);
        assertTrue(d.accept(byPost));
        assertTrue(d.accept(byMedia)); // 不同 timelineKey（post-1 vs media-x）→ 都接纳
        assertEquals(2, d.distinctCount());
    }

    @Test
    void freshInstance_startsEmpty() {
        TimelineDedup d = new TimelineDedup();
        assertEquals(0, d.distinctCount());
        assertTrue(d.accept(item("p1")));
        assertEquals(1, d.distinctCount());
    }
}
