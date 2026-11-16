package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.ReviewTaskRepository;
import com.turbofeed.gateway.service.behavior.BehaviorLogService;
import com.turbofeed.gateway.service.moderation.ContentSecurityService;
import com.turbofeed.gateway.service.moderation.ModerationHitRepository;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 评审优化项④：读路径接线护栏。
 *
 * <p>锁死三件事：① 缓存命中时完全不打 DB；② 点赞数 / 评论总数仍在缓存外实时读 Redis，
 * 不被 TTL 冻住；③ 缓存 Bean 缺失或顶层 / 回复两条读路径都按预期回源。</p>
 */
@ExtendWith(MockitoExtension.class)
class CommentServicePageCacheTest {

    @Mock CommentJdbcRepository commentRepository;
    @Mock ContentSecurityService contentSecurityService;
    @Mock SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock CommentRiskService commentRiskService;
    @Mock UploadRateLimiter rateLimiter;
    @Mock ReviewTaskRepository reviewTaskRepository;
    @Mock ModerationHitRepository moderationHitRepository;
    @Mock MediaProperties properties;
    @Mock BehaviorLogService behaviorLogService;
    @Mock CommentCountService commentCountService;
    @Mock CommentHotService commentHotService;
    @Mock CommentLikeService commentLikeService;
    @Mock CommentEventPublisher commentEventPublisher;
    @Mock NotificationGenerator notificationGenerator;
    @Mock CommentPageCache pageCache;

    @InjectMocks CommentService service;

    @BeforeEach
    void setUp() {
        // pageCache 是可选字段注入（非 final），显式回填一次，避免依赖 Mockito 的字段注入策略
        ReflectionTestUtils.setField(service, "pageCache", pageCache);
    }

    private static Comment row(long id, String content, int likeCount) {
        return new Comment(id, "m1", 9L, 0L, 0L, content,
                CommentStatus.APPROVED, likeCount, Instant.parse("2026-11-16T10:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static Supplier<List<Comment>> anyLoader() {
        return any(Supplier.class);
    }

    @Test
    void cacheHit_skipsDb_butLikeCountAndTotalStayRealtime() {
        when(pageCache.load(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyLoader()))
                .thenReturn(new ArrayList<>(List.of(row(1L, "cached", 0))));
        when(commentLikeService.likeCounts(anyList())).thenReturn(Map.of(1L, 7L));
        when(commentCountService.getCount(anyString())).thenReturn(12L);

        CommentPage page = service.listTopLevel("m1", 20, "", "newest");

        verifyNoInteractions(commentRepository);
        assertEquals(1, page.items().size());
        assertEquals("cached", page.items().get(0).content());
        assertEquals(7, page.items().get(0).likeCount(), "点赞数必须在缓存外实时回填，不能被 TTL 冻住");
        assertEquals(12L, page.total(), "评论总数必须在缓存外实时读 Redis");
        assertEquals("", page.nextCursor());

        ArgumentCaptor<String> mode = ArgumentCaptor.forClass(String.class);
        verify(pageCache).load(eq("m1"), eq(0L), eq(20), eq(""), mode.capture(), anyLoader());
        assertEquals("newest", mode.getValue());
    }

    @Test
    void cacheBeanAbsent_fallsBackToDbWithZeroRegression() {
        ReflectionTestUtils.setField(service, "pageCache", null);
        when(commentHotService.recentHead(anyString(), anyInt())).thenReturn(List.of());
        when(commentRepository.listTopLevelFresh(anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(row(2L, "from-db", 0))));
        when(commentLikeService.likeCounts(anyList())).thenReturn(Map.of());
        when(commentCountService.getCount(anyString())).thenReturn(1L);

        CommentPage page = service.listTopLevel("m1", 20, "", "newest");

        verify(commentRepository).listTopLevelFresh("m1", 20);
        assertEquals("from-db", page.items().get(0).content());
        assertEquals(1L, page.total());
    }

    @Test
    void hotMode_keepsItsOwnCacheDimension() {
        when(pageCache.load(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyLoader()))
                .thenReturn(new ArrayList<>(List.of(row(3L, "hot", 0))));
        when(commentLikeService.likeCounts(anyList())).thenReturn(Map.of());
        when(commentCountService.getCount(anyString())).thenReturn(3L);

        CommentPage page = service.listTopLevel("m1", 20, "", "hot");

        ArgumentCaptor<String> mode = ArgumentCaptor.forClass(String.class);
        verify(pageCache).load(eq("m1"), eq(0L), eq(20), eq(""), mode.capture(), anyLoader());
        assertEquals("hot", mode.getValue(), "最热 / 最新必须落在不同缓存维度，避免串页");
        assertEquals(1, page.items().size());
        verifyNoInteractions(commentRepository);
    }

    @Test
    void listReplies_usesSameCachePath_withRootIdDimension() {
        when(pageCache.load(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyLoader()))
                .thenReturn(new ArrayList<>(List.of(row(4L, "reply", 0))));
        when(commentLikeService.likeCounts(anyList())).thenReturn(Map.of());

        CommentPage page = service.listReplies("m1", 9L, 50, "");

        verify(pageCache).load(eq("m1"), eq(9L), eq(50), eq(""), eq("replies"), anyLoader());
        assertEquals(1, page.items().size());
        assertEquals(0L, page.total(), "回复列表无独立总数，保持 0");
        verifyNoInteractions(commentRepository);
    }
}
