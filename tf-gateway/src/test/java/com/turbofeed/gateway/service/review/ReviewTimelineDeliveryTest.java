package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.repository.MediaTagJdbcRepository;
import com.turbofeed.gateway.service.event.outbox.OutboxEventType;
import com.turbofeed.gateway.service.event.outbox.OutboxService;
import com.turbofeed.gateway.service.event.outbox.TimelineAppendPayload;
import com.turbofeed.gateway.service.query.MediaItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-1 靶心：时间线投递（#310 从 MediaReviewService 拆出）。
 * 锁定「入流 = 同事务落发件箱事件 + 提交后直投 + 内容标签冷存」「缓存失效清整帖全部行」。
 */
@ExtendWith(MockitoExtension.class)
class ReviewTimelineDeliveryTest {

    @Mock MediaJdbcRepository mediaRepository;
    @Mock MediaTagJdbcRepository mediaTagRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock OutboxService outboxService;
    @Captor ArgumentCaptor<Set<String>> keysCaptor;

    private ReviewTimelineDelivery delivery() {
        return new ReviewTimelineDelivery(mediaRepository, mediaTagRepository, redisTemplate, outboxService);
    }

    @Test
    void publishAppend_enqueuesAndSavesTags() {
        MediaItem post = new MediaItem("post/42", "media/42/a.jpg", "cover", List.of("u1"), 0,
                MediaStatus.APPROVED, Instant.EPOCH, "cap", "mark", List.of("tag1"), 0L, 0L, false);
        when(outboxService.enqueue(eq(OutboxEventType.TIMELINE_APPEND), anyString(), anyLong(), any())).thenReturn(99L);

        delivery().publishAppend("media/42/a.jpg", 42L, post, 1);

        verify(outboxService).enqueue(eq(OutboxEventType.TIMELINE_APPEND), eq("media/42/a.jpg"), eq(42L),
                any(TimelineAppendPayload.class));
        verify(outboxService).deliverAfterCommit(99L);
        verify(mediaTagRepository).save(eq(post.timelineKey()), eq(post.tags()));
    }

    @Test
    void evictCache_deletesAllPostRows() {
        String mediaId = "media/42/a.jpg";
        long userId = 42L;
        when(mediaRepository.findPostId(mediaId, userId)).thenReturn("post/42");
        MediaItem r1 = mock(MediaItem.class);
        when(r1.mediaId()).thenReturn("media/42/a.jpg");
        MediaItem r2 = mock(MediaItem.class);
        when(r2.mediaId()).thenReturn("media/42/b.jpg");
        when(mediaRepository.listPostImages(userId, "post/42")).thenReturn(List.of(r1, r2));

        delivery().evictCache(mediaId, userId);

        verify(redisTemplate).delete(keysCaptor.capture());
        Set<String> keys = keysCaptor.getValue();
        assertEquals(2, keys.size());
        assertTrue(keys.contains("tf:media:status:media/42/a.jpg"));
        assertTrue(keys.contains("tf:media:status:media/42/b.jpg"));
    }
}
