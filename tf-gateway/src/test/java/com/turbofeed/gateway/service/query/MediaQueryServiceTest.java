package com.turbofeed.gateway.service.query;

import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.gateway.client.FeedRecommendedPage;
import com.turbofeed.gateway.config.FeedEngineProperties;
import com.turbofeed.gateway.repository.MediaJdbcRepository;
import com.turbofeed.gateway.service.feed.InteractionEventService;
import com.turbofeed.gateway.service.like.MediaLikeService;
import com.turbofeed.gateway.service.review.MediaStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P3-PR07 回归护栏：网关侧 {@link MediaQueryService#listRecommended} 的 keyset 游标路由与降级形态。
 *
 * <p>纯 Mockito、不启 Spring。锁定三件事：① 开关开启且游标非空 → 向引擎传 cursor（keyset 分页）；
 * ② 空游标 / 开关关闭 → 回退页码分页（传 null cursor，兼容老调用方与回滚）；
 * ③ 引擎不可用时降级仍返回 {@link FeedRecommendedPage}（接口形态不变，绝不跨分片广播）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaQueryServiceTest {

    @Mock MediaJdbcRepository mediaRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock FeedEngineClient feedEngineClient;
    @Mock FeedEngineProperties feedProperties;
    @Mock InteractionEventService interactionEventService;
    @Mock MediaLikeService mediaLikeService;

    private MediaQueryService service() {
        return new MediaQueryService(mediaRepository, redisTemplate, feedEngineClient,
                feedProperties, interactionEventService, mediaLikeService);
    }

    /** {@code cursorPagingEnabled} 是 {@code @Value} 非 final 字段，单测里用反射注入（无 Spring 上下文）。 */
    private void setCursorEnabled(MediaQueryService svc, boolean enabled) throws Exception {
        Field f = MediaQueryService.class.getDeclaredField("cursorPagingEnabled");
        f.setAccessible(true);
        f.set(svc, enabled);
    }

    private static MediaItem item(String postId) {
        return new MediaItem(postId, "media/x", "http://x/" + postId, MediaStatus.APPROVED,
                Instant.now(), "cap", null);
    }

    /** 让 enrichCounts 在纯 mock 环境下不报错（计数按 0 / 未赞处理）。 */
    private void stubEnrich() {
        when(mediaLikeService.likeCounts(anyList())).thenReturn(Map.of());
        when(mediaLikeService.isLikedBatch(anyList(), anyLong())).thenReturn(Map.of());
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of());
    }

    @Test
    void cursorEnabled_withCursor_passesCursorToClient_andWrapsPage() throws Exception {
        MediaQueryService svc = service();
        setCursorEnabled(svc, true);
        stubEnrich();
        FeedRecommendedPage clientPage = new FeedRecommendedPage(List.of(item("post/1/a")), "NEXT", true);
        when(feedEngineClient.recommended(anyInt(), anyInt(), any(), any())).thenReturn(Optional.of(clientPage));

        FeedRecommendedPage result = svc.listRecommended(0, 20, "1001", "SOMECURSOR");

        ArgumentCaptor<String> cursorCap = ArgumentCaptor.forClass(String.class);
        verify(feedEngineClient).recommended(eq(0), eq(20), eq("1001"), cursorCap.capture());
        assertEquals("SOMECURSOR", cursorCap.getValue(), "开关开启 + 游标非空必须走 keyset 游标分页");
        assertEquals("NEXT", result.nextCursor());
        assertTrue(result.hasMore());
        assertEquals(1, result.items().size());
    }

    @Test
    void cursorEnabled_blankCursor_fallsBackToPageMode() throws Exception {
        MediaQueryService svc = service();
        setCursorEnabled(svc, true);
        stubEnrich();
        FeedRecommendedPage clientPage = new FeedRecommendedPage(List.of(item("post/1/a")), "", false);
        when(feedEngineClient.recommended(anyInt(), anyInt(), any(), any())).thenReturn(Optional.of(clientPage));

        svc.listRecommended(3, 20, "1001", "");

        ArgumentCaptor<String> cursorCap = ArgumentCaptor.forClass(String.class);
        verify(feedEngineClient).recommended(eq(3), eq(20), eq("1001"), cursorCap.capture());
        assertNull(cursorCap.getValue(), "空游标必须回退页码分页（传 null cursor）");
    }

    @Test
    void cursorDisabled_ignoresCursor_usesPageMode() throws Exception {
        MediaQueryService svc = service();
        setCursorEnabled(svc, false);
        stubEnrich();
        FeedRecommendedPage clientPage = new FeedRecommendedPage(List.of(item("post/1/a")), "", false);
        when(feedEngineClient.recommended(anyInt(), anyInt(), any(), any())).thenReturn(Optional.of(clientPage));

        svc.listRecommended(2, 20, "1001", "SOMECURSOR");

        ArgumentCaptor<String> cursorCap = ArgumentCaptor.forClass(String.class);
        verify(feedEngineClient).recommended(eq(2), eq(20), eq("1001"), cursorCap.capture());
        assertNull(cursorCap.getValue(), "开关关闭时即便传入 cursor 也必须回退页码分页（兼容/回滚）");
    }

    @Test
    void engineUnavailable_degradedEmpty_returnsEmptyPage() {
        MediaQueryService svc = service();
        when(feedProperties.getDegradedMode()).thenReturn(FeedEngineProperties.DegradedMode.EMPTY);
        when(feedEngineClient.recommended(anyInt(), anyInt(), any(), any())).thenReturn(Optional.empty());

        FeedRecommendedPage result = svc.listRecommended(0, 20, "1001", null);

        assertTrue(result.items().isEmpty());
        assertFalse(result.hasMore());
        assertEquals("", result.nextCursor());
        verify(mediaRepository, never()).listApprovedGlobal(anyInt(), anyLong());
    }

    @Test
    void engineUnavailable_degradedLocalScan_returnsEnrichedPage() {
        MediaQueryService svc = service();
        stubEnrich();
        when(feedProperties.getDegradedMode()).thenReturn(FeedEngineProperties.DegradedMode.LOCAL_SCAN);
        when(feedEngineClient.recommended(anyInt(), anyInt(), any(), any())).thenReturn(Optional.empty());
        when(mediaRepository.listApprovedGlobal(anyInt(), anyLong())).thenReturn(List.of(item("post/1/a")));

        FeedRecommendedPage result = svc.listRecommended(0, 20, "1001", null);

        assertEquals(1, result.items().size());
        assertEquals("", result.nextCursor());
        assertFalse(result.hasMore());
        verify(mediaRepository).listApprovedGlobal(eq(20), eq(0L));
    }
}
