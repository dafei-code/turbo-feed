package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.redis.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link ItemCfRecallChannel} 单测（纯 Mockito，无中间件）。
 * 覆盖：种子→共现 merge、权重降序、排除集（hot/interest/followee/已收藏种子）、空种子、匿名、fail-open。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ItemCfRecallChannelTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock RecallSupport recallSupport;

    private ItemCfRecallChannel channel;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        channel = new ItemCfRecallChannel(redisTemplate, recallSupport);
    }

    /** 构造最小 RecallContext（仅 userId 与非空排除集相关字段有意义）。 */
    private RecallContext ctx(String userId, Set<String> hot, Set<String> interest,
                              Set<String> followees) {
        RecallContext c = new RecallContext(userId, 0, 20, null, Map.of(), Map.of(),
                List.of(), Set.of(), null, followees, Map.of(), recallSupport);
        c.hotKeys.addAll(hot);
        c.interestKeys.addAll(interest);
        return c;
    }

    private static FeedItemView item(String postId) {
        return new FeedItemView(postId, null, null, List.of(), "APPROVED",
                Instant.now(), null, null, List.of(), 1);
    }

    @Test
    void recall_mergesNeighborsByWeightDesc() {
        when(zSetOps.reverseRange(RedisKeys.userFavorite(42L), 0, 19)).thenReturn(Set.of("A"));
        when(zSetOps.reverseRangeByScoreWithScores(eq("tf:cf:item:A"), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of(new DefaultTypedTuple<>("B", 2.0), new DefaultTypedTuple<>("C", 1.0)));
        when(recallSupport.memberOf("B")).thenReturn(item("B"));
        when(recallSupport.memberOf("C")).thenReturn(item("C"));

        List<ScoredItem> part = channel.recall(ctx("42", Set.of(), Set.of(), Set.of()), 10);

        assertEquals(2, part.size());
        assertEquals("B", part.get(0).item().timelineKey()); // 权重 2.0 在前
        assertEquals("C", part.get(1).item().timelineKey()); // 权重 1.0 在后
    }

    @Test
    void recall_excludesSeedItself() {
        when(zSetOps.reverseRange(RedisKeys.userFavorite(42L), 0, 19)).thenReturn(Set.of("A"));
        when(zSetOps.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of(new DefaultTypedTuple<>("A", 5.0), new DefaultTypedTuple<>("B", 1.0)));
        when(recallSupport.memberOf("B")).thenReturn(item("B"));

        List<ScoredItem> part = channel.recall(ctx("42", Set.of(), Set.of(), Set.of()), 10);

        assertEquals(1, part.size());
        assertEquals("B", part.get(0).item().timelineKey());
    }

    @Test
    void recall_excludesHotAndFollowee() {
        // B 在 hotKeys；C 作者是 99（关注作者）→ 两者均被排除，只剩 D
        when(zSetOps.reverseRange(RedisKeys.userFavorite(42L), 0, 19)).thenReturn(Set.of("A"));
        when(zSetOps.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of(new DefaultTypedTuple<>("B", 3.0),
                        new DefaultTypedTuple<>("C", 2.0), new DefaultTypedTuple<>("D", 1.0)));
        when(recallSupport.memberOf("B")).thenReturn(item("B"));
        when(recallSupport.memberOf("C")).thenReturn(item("post/99/xyz"));
        when(recallSupport.memberOf("D")).thenReturn(item("D"));

        List<ScoredItem> part = channel.recall(ctx("42", Set.of("B"), Set.of(), Set.of("99")), 10);

        assertEquals(1, part.size());
        assertEquals("D", part.get(0).item().timelineKey());
    }

    @Test
    void recall_emptySeedsReturnsEmpty() {
        when(zSetOps.reverseRange(RedisKeys.userFavorite(42L), 0, 19)).thenReturn(Set.of());

        List<ScoredItem> part = channel.recall(ctx("42", Set.of(), Set.of(), Set.of()), 10);

        assertTrue(part.isEmpty());
    }

    @Test
    void recall_nullUserIdReturnsEmpty() {
        List<ScoredItem> part = channel.recall(ctx(null, Set.of(), Set.of(), Set.of()), 10);
        assertTrue(part.isEmpty());
    }

    @Test
    void recall_failOpenOnRedisError() {
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong())).thenThrow(new RuntimeException("boom"));

        assertTrue(channel.recall(ctx("42", Set.of(), Set.of(), Set.of()), 10).isEmpty());
    }

    @Test
    void typeAndPhase() {
        assertEquals(RecallType.CF, channel.type());
        assertEquals(RecallPhase.PRE_POOL, channel.phase());
    }
}
