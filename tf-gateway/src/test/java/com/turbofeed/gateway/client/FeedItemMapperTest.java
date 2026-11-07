package com.turbofeed.gateway.client;

import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADR-0015：引擎契约 → 网关视图时逐 URL 重签（推荐流读取侧 403 修复）。
 *
 * <p>映射层保持纯静态，重签能力以 {@code UnaryOperator<String>} 注入；这里锁定
 * 封面/多图都被重写、无参重载保持原样、历史单图回退、未知状态降级为 PENDING。</p>
 */
class FeedItemMapperTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private static FeedItemView item(List<String> images, String status) {
        return new FeedItemView("post/42/u", "media/42/a.jpg", "cover-old", images, status,
                CREATED, "caption", "[]", List.of("tag"));
    }

    @Test
    void toView_withRewriter_resignsCoverAndEveryImage() {
        FeedItemView v = item(List.of("img1-old", "img2-old"), FeedItemView.STATUS_APPROVED);

        MediaItem view = FeedItemMapper.toView(v, u -> u + "-fresh");

        assertEquals("cover-old-fresh", view.url());
        assertEquals(List.of("img1-old-fresh", "img2-old-fresh"), view.images());
        assertEquals("post/42/u", view.postId());
        assertEquals("media/42/a.jpg", view.mediaId());
        assertEquals(MediaStatus.APPROVED, view.status());
        assertEquals(CREATED, view.createdAt());
        assertEquals(List.of("tag"), view.tags());
    }

    @Test
    void toView_withoutRewriter_keepsOriginalUrls() {
        FeedItemView v = item(List.of("img1-old", "img2-old"), FeedItemView.STATUS_APPROVED);

        MediaItem view = FeedItemMapper.toView(v);

        assertEquals("cover-old", view.url());
        assertEquals(List.of("img1-old", "img2-old"), view.images());
    }

    @Test
    void toView_legacySingleImage_fallsBackToResignedCover() {
        FeedItemView v = item(null, FeedItemView.STATUS_APPROVED);

        MediaItem view = FeedItemMapper.toView(v, u -> u + "-fresh");

        assertEquals(List.of("cover-old-fresh"), view.images());
    }

    @Test
    void toView_unknownStatus_fallsBackToPending() {
        MediaItem view = FeedItemMapper.toView(item(List.of("i"), "FUTURE_STATUS"));

        assertEquals(MediaStatus.PENDING, view.status());
    }

    @Test
    void toView_nullItemOrNullRewriter_isSafe() {
        assertNull(FeedItemMapper.toView(null, u -> u));
        assertNull(FeedItemMapper.toView(null));

        MediaItem view = FeedItemMapper.toView(item(List.of("i"), FeedItemView.STATUS_APPROVED), null);
        assertEquals(List.of("i"), view.images());
        assertEquals("cover-old", view.url());
    }
}
