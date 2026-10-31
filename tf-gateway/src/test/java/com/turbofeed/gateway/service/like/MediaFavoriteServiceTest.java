package com.turbofeed.gateway.service.like;

import com.turbofeed.gateway.service.counter.CounterEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorEventPublisher;
import com.turbofeed.gateway.service.feed.BehaviorReport;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisSetCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 收藏服务回归护栏（镜像 {@link MediaLikeServiceTest}）。
 *
 * <p>纯 Mockito、不启 Spring、不需要 Docker。锁定五类易回归行为 + 我的收藏列表 per-user ZSET
 * + 行为事件类型（接推荐赛马 / 用户画像）：</p>
 * <ul>
 *   <li>① <b>写路径同步</b>：只有实际状态变化（SADD/SREM 返回 1）才动计数 Key；重复收藏（返回 0）绝不能再次 INCR。</li>
 *   <li>② <b>读路径 O(1)</b>：{@code favoriteCount} 走计数 Key 的 {@code GET}、批量走一次 {@code MGET}。</li>
 *   <li>③ <b>冷启动回退</b>：计数 Key 缺失时回退 64 槽求和并预热写回。</li>
 *   <li>④ <b>灾备重建</b>：{@code rebuildFromDb} / {@code rebuildUserFavorites} 用 {@code media_favorite} 表恢复。</li>
 *   <li>⑤ <b>fail-open</b>：Redis 异常不得抛给用户主流程。</li>
 *   <li>⑥ <b>我的收藏列表</b>：per-user ZSET 的写维护与 {@code myFavorites} 游标分页。</li>
 *   <li>⑦ <b>接推荐</b>：实际状态变化才发 {@code FAVORITE}/{@code UNFAVORITE} 行为事件。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaFavoriteServiceTest {

    private static final String POST_ID = "post/42/abc";
    private static final String COUNT_KEY = "tf:favorite:count:" + POST_ID;
    private static final String USER_FAV_KEY = "tf:favorite:user:42";
    private static final int SLOT_COUNT = 64;

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock BehaviorEventPublisher behaviorPublisher;
    @Mock MediaFavoriteJdbcRepository favoriteRepository;
    @Mock ItemCooccurrenceService itemCooccurrenceService;
    @Mock SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock UploadRateLimiter rateLimiter;
    @Mock ObjectProvider<CounterEventPublisher> counterPublisher;
    @Mock CounterEventPublisher counterEventPublisher;
    @Mock NotificationGenerator notificationGenerator;
    @Mock RedisConnection connection;
    @Mock RedisKeyCommands keyCommands;
    @Mock RedisStringCommands stringCommands;
    @Mock RedisSetCommands setCommands;

    /** 直接执行的 Executor：让异步落库在测试中同步完成，便于断言。 */
    private final Executor directExecutor = Runnable::run;

    private final Deque<List<Object>> pipelineResults = new ArrayDeque<>();

    /**
     * 计数脚本返回值：非 {@code null} = 热路径（Key 原本存在）；{@code null} = 冷启动。
     */
    private Long scriptReturn = 1L;

    private MediaFavoriteService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(connection.setCommands()).thenReturn(setCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback<?> cb = inv.getArgument(0);
            cb.doInRedis(connection);
            List<Object> r = pipelineResults.poll();
            return r != null ? r : List.of();
        });
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenAnswer(inv -> scriptReturn);
        // 默认兜底（多数测试不需要命中这些）
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        when(zSetOps.remove(anyString(), any())).thenReturn(1L);
        when(zSetOps.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of());
        when(zSetOps.score(anyString(), any())).thenReturn(null);
        service = new MediaFavoriteService(redisTemplate, behaviorPublisher, favoriteRepository,
                snowflakeIdGenerator, directExecutor, rateLimiter, itemCooccurrenceService, counterPublisher, notificationGenerator);
    }

    /** 构造 N 项 × 64 槽的 SCARD 结果，使第 idx 项的 64 槽之和恰为 wantSum。 */
    private static List<Object> slotSums(int items, int... wantSums) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            int want = i < wantSums.length ? wantSums[i] : 0;
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                out.add(slot == 0 ? (long) want : 0L);
            }
        }
        return out;
    }

    // ===== ① 写路径同步：只有实际状态变化才动计数 =====

    @Test
    void firstFavorite_bumpsCountKeyViaScript() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);

        assertTrue(service.apply(POST_ID, 42L, true));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("1"), anyString());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void duplicateFavorite_doesNotTouchCountKey() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);

        assertTrue(service.apply(POST_ID, 42L, true), "已收藏仍应返回 true（抖音式幂等）");

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void unfavorite_bumpsCountKeyWithNegativeDelta() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("42"))).thenReturn(1L);

        assertFalse(service.apply(POST_ID, 42L, false));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("-1"), anyString());
    }

    @Test
    void unfavoriteWhenNotFavorited_doesNotTouchCountKey() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("42"))).thenReturn(0L);

        service.apply(POST_ID, 42L, false);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void rateLimited_throwsAndTouchesNothing() {
        when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(false);

        assertThrows(com.turbofeed.gateway.exception.BizException.class,
                () -> service.apply(POST_ID, 42L, true));
        verify(setOps, never()).add(anyString(), anyString());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    // ===== ①-c 去重集合回收兜底 =====

    @Test
    void slotMissingAndDbHasRecord_doesNotBumpCount() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        when(favoriteRepository.existsFavorite(POST_ID, 42L)).thenReturn(true);

        assertTrue(service.apply(POST_ID, 42L, true), "收藏态仍应为已收藏（成员已恢复）");

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
        verify(valueOps, never()).increment(anyString(), anyLong());
        verify(favoriteRepository, never()).insert(any());
    }

    @Test
    void slotExists_doesNotQueryDb() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);

        assertTrue(service.apply(POST_ID, 42L, true));

        verify(favoriteRepository, never()).existsFavorite(anyString(), anyLong());
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("1"), anyString());
    }

    @Test
    void slotMissingButDbEmpty_stillBumpsCount() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        when(favoriteRepository.existsFavorite(POST_ID, 42L)).thenReturn(false);

        assertTrue(service.apply(POST_ID, 42L, true));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("1"), anyString());
        verify(favoriteRepository).insert(any());
    }

    // ===== ①-b 冷启动校正 =====

    @Test
    void countKeyMissing_coldStartRecalculatesFromSlots() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        scriptReturn = null;
        pipelineResults.add(slotSums(1, 500));
        when(valueOps.setIfAbsent(eq(COUNT_KEY), eq("500"), any())).thenReturn(true);

        service.apply(POST_ID, 42L, true);

        verify(valueOps).setIfAbsent(eq(COUNT_KEY), eq("500"), any());
        verify(valueOps, never()).increment(eq(COUNT_KEY), eq(1L));
    }

    @Test
    void coldStart_concurrentInit_appliesDeltaInsteadOfOverwriting() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        scriptReturn = null;
        pipelineResults.add(slotSums(1, 500));
        when(valueOps.setIfAbsent(eq(COUNT_KEY), anyString(), any())).thenReturn(false);

        service.apply(POST_ID, 42L, true);

        verify(valueOps).increment(COUNT_KEY, 1L);
    }

    // ===== ② 读路径 O(1) =====

    @Test
    void favoriteCount_readsCountKeyOnce_withoutScanningSlots() {
        when(valueOps.get(COUNT_KEY)).thenReturn("128");

        assertEquals(128L, service.favoriteCount(POST_ID));

        verify(connection, never()).sCard(any(byte[].class));
    }

    @Test
    void favoriteCountBatch_warmHit_usesSingleMget_andNoPipeline() {
        when(valueOps.multiGet(anyList())).thenReturn(List.of("10", "20"));

        Map<String, Long> counts = service.favoriteCounts(List.of("p1", "p2"));

        assertEquals(10L, counts.get("p1"));
        assertEquals(20L, counts.get("p2"));
        verify(valueOps, times(1)).multiGet(anyList());
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    // ===== ③ 冷启动回退 =====

    @Test
    void favoriteCount_fallsBackToSlots_whenCountKeyMissing() {
        when(valueOps.get(COUNT_KEY)).thenReturn(null);
        // favoriteCount 回退路径只触发一次 pipeline（64 槽 SCARD 求和），无 A4 续期 pipeline
        pipelineResults.add(List.of(3L, 4L, 0L));

        assertEquals(7L, service.favoriteCount(POST_ID));
    }

    // ===== ④ 灾备重建 =====

    @Test
    void rebuildFromDb_restoresCount() {
        when(favoriteRepository.listUserIds(POST_ID)).thenReturn(List.of(1001L, 1002L, 1003L));

        assertEquals(3L, service.rebuildFromDb(POST_ID));

        verify(valueOps).set(eq(COUNT_KEY), eq("3"), any());
        verify(favoriteRepository).listUserIds(POST_ID);
    }

    @Test
    void rebuildUserFavorites_restoresZSet() {
        when(favoriteRepository.listPostIds(42L)).thenReturn(List.of("pA", "pB"));

        assertEquals(2L, service.rebuildUserFavorites(42L));

        verify(redisTemplate).delete(USER_FAV_KEY);
        verify(zSetOps, times(2)).add(eq(USER_FAV_KEY), anyString(), anyDouble());
        verify(redisTemplate).expire(eq(USER_FAV_KEY), any());
    }

    // ===== ⑤ fail-open =====

    @Test
    void apply_redisFailureIsFailOpen_andDoesNotThrow() {
        when(setOps.add(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(assertDoesNotThrow(() -> service.apply(POST_ID, 42L, true)),
                "fail-open：按请求态兜底，不得把 Redis 异常抛给主流程");
    }

    // ===== ⑥ 我的收藏列表（per-user ZSET） =====

    @Test
    void myFavorites_paginatesByCursor() {
        when(zSetOps.reverseRangeByScore(eq(USER_FAV_KEY), anyDouble(), anyDouble(), eq(0L), eq(2L)))
                .thenReturn(new LinkedHashSet<>(List.of("pA", "pB")));
        when(zSetOps.score(eq(USER_FAV_KEY), eq("pB"))).thenReturn(999.0);

        MediaFavoriteService.FavoriteList page = service.myFavorites(42L, 0L, 2);

        assertEquals(List.of("pA", "pB"), page.postIds());
        assertEquals(999L, page.nextCursor());
    }

    @Test
    void myFavorites_emptyWhenRedisFails() {
        when(zSetOps.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        MediaFavoriteService.FavoriteList page = service.myFavorites(42L, 0L, 20);

        assertTrue(page.postIds().isEmpty());
        assertEquals(0L, page.nextCursor());
    }

    @Test
    void apply_favorite_maintainsUserFavZSet() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);

        service.apply(POST_ID, 42L, true);

        verify(zSetOps).add(eq(USER_FAV_KEY), eq(POST_ID), anyDouble());
        verify(redisTemplate).expire(eq(USER_FAV_KEY), any());
    }

    @Test
    void apply_unfavorite_removesFromUserFavZSet() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("42"))).thenReturn(1L);

        service.apply(POST_ID, 42L, false);

        verify(zSetOps).remove(eq(USER_FAV_KEY), eq(POST_ID));
    }

    // ===== ⑦ 接推荐赛马 / 用户画像：行为事件类型 =====

    @Test
    void apply_favorite_publishesFavoriteEvent() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);

        service.apply(POST_ID, 42L, true);

        verify(behaviorPublisher).report(
                eq(List.of(new BehaviorReport(POST_ID, "FAVORITE", null, null, null, null))),
                eq("42"));
    }

    @Test
    void apply_unfavorite_publishesUnfavoriteEvent() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("42"))).thenReturn(1L);

        service.apply(POST_ID, 42L, false);

        verify(behaviorPublisher).report(
                eq(List.of(new BehaviorReport(POST_ID, "UNFAVORITE", null, null, null, null))),
                eq("42"));
    }

    @Test
    void apply_favorite_notifiesCounterWithFavoriteType() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        doAnswer(inv -> {
            ((java.util.function.Consumer<CounterEventPublisher>) inv.getArgument(0)).accept(counterEventPublisher);
            return null;
        }).when(counterPublisher).ifAvailable(any());

        service.apply(POST_ID, 42L, true);

        verify(counterEventPublisher).incr(POST_ID, "FAVORITE", 1);
    }

    @Test
    void apply_favorite_triggersCooccurrenceOnNewFavorite() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);

        service.apply(POST_ID, 42L, true);

        // 新收藏主链路异步块应触发 item-CF 共现增量维护（directExecutor 同步完成）
        verify(itemCooccurrenceService).onFavorite(POST_ID, 42L);
    }

    @Test
    void apply_unfavorite_triggersCooccurrenceDecay() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("42"))).thenReturn(1L);

        service.apply(POST_ID, 42L, false);

        // 取消收藏主链路异步块应触发 item-CF 共现对称衰减
        verify(itemCooccurrenceService).onUnfavorite(POST_ID, 42L);
    }
}
