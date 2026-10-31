package com.turbofeed.gateway.service.like;

import com.turbofeed.gateway.service.feed.BehaviorEventPublisher;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A1/A4 回归护栏：点赞计数从「64 槽 SCARD 求和」改为「独立计数 Key」后的正确性契约。
 *
 * <p>纯 Mockito、不启 Spring、不需要 Docker。锁定五类易回归且后果严重的行为：</p>
 * <ul>
 *   <li>① <b>写路径同步</b>：只有实际状态变化（SADD/SREM 返回 1）才动计数 Key；
 *       重复点赞（返回 0）绝不能再次 INCR，否则计数虚增。</li>
 *   <li>② <b>读路径 O(1)</b>：{@code likeCount} 走计数 Key 的 {@code GET}、批量走一次 {@code MGET}，
 *       热命中时不再触发 64 次 {@code SCARD}（A1 的核心收益，退化即回归）。</li>
 *   <li>③ <b>冷启动回退</b>：计数 Key 缺失时回退 64 槽求和并预热写回，且回退与预热
 *       <b>合并为常数次往返</b>，不得退化成「每项一次」。</li>
 *   <li>④ <b>A4 灾备重建</b>：{@code rebuildFromDb} 用 {@code media_like} 表恢复计数，
 *       使「DB 落库为灾备重建用」从注释承诺变成可执行能力。</li>
 *   <li>⑤ <b>fail-open</b>：Redis 异常不得抛给用户主流程。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaLikeServiceTest {

    private static final String POST_ID = "post/42/abc";
    private static final String COUNT_KEY = "tf:like:count:" + POST_ID;
    private static final int SLOT_COUNT = 64;

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;
    @Mock BehaviorEventPublisher behaviorPublisher;
    @Mock MediaLikeJdbcRepository likeRepository;
    @Mock SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock UploadRateLimiter rateLimiter;
    @Mock ObjectProvider<com.turbofeed.gateway.service.counter.CounterEventPublisher> counterPublisher;
    @Mock NotificationGenerator notificationGenerator;
    @Mock RedisConnection connection;
    @Mock RedisKeyCommands keyCommands;
    @Mock RedisStringCommands stringCommands;
    @Mock RedisSetCommands setCommands;

    /** 直接执行的 Executor：让异步落库在测试中同步完成，便于断言。 */
    private final Executor directExecutor = Runnable::run;

    /** 按调用顺序返回的 pipeline 结果队列（executePipelined 每次调用取一个）。 */
    private final Deque<List<Object>> pipelineResults = new ArrayDeque<>();

    /**
     * 计数脚本（{@code LikeCountScript.COUNT_BUMP}）的返回值：
     * 非 {@code null} = 热路径（Key 原本存在，已完成 INCRBY + PEXPIRE）；
     * {@code null} = 冷启动（Key 原本不存在，调用方须按 64 槽去重集合重算校正）。
     */
    private Long scriptReturn = 1L;

    private MediaLikeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        // pipeline 回调里用到的三类子命令必须打桩，否则返回 null → NPE
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(connection.setCommands()).thenReturn(setCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback<?> cb = inv.getArgument(0);
            cb.doInRedis(connection);
            List<Object> r = pipelineResults.poll();
            return r != null ? r : List.of();
        });
        // 计数维护脚本：A1 起热路径走脚本（INCRBY+PEXPIRE 一次往返），不再直接调 increment
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenAnswer(inv -> scriptReturn);
        service = new MediaLikeService(redisTemplate, behaviorPublisher, likeRepository,
                snowflakeIdGenerator, directExecutor, rateLimiter, counterPublisher, notificationGenerator);
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
    void firstLike_bumpsCountKeyViaScript() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);

        assertTrue(service.apply(POST_ID, 42L, true));

        // A1 起计数维护走脚本（EXISTS + INCRBY + PEXPIRE 一次往返），不再直接调 increment
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("1"), anyString());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void duplicateLike_doesNotTouchCountKey() {
        // SADD 返回 0 = 已赞（幂等），绝不能重复计数
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);

        assertTrue(service.apply(POST_ID, 42L, true), "已赞仍应返回 true（抖音式幂等）");

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void unlike_bumpsCountKeyWithNegativeDelta() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("42"))).thenReturn(1L);

        assertFalse(service.apply(POST_ID, 42L, false));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("-1"), anyString());
    }

    @Test
    void unlikeWhenNotLiked_doesNotTouchCountKey() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("42"))).thenReturn(0L);

        service.apply(POST_ID, 42L, false);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void rateLimited_throwsAndTouchesNothing() {
        when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(false);

        assertThrows(com.turbofeed.gateway.exception.BizException.class,
                () -> service.apply(POST_ID, 42L, true));
        verify(setOps, never()).add(anyString(), anyString());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    // ===== ①-c 去重集合回收兜底：集合没了，红心态可以错，计数不能虚增 =====

    @Test
    void slotMissingAndDbHasRecord_doesNotBumpCount() {
        // 去重集合被 TTL 回收（hasKey=false），但灾备表证明该用户此前赞过
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        when(likeRepository.existsLike(POST_ID, 42L)).thenReturn(true);

        assertTrue(service.apply(POST_ID, 42L, true), "红心态仍应为已赞（成员已恢复）");

        // 关键：不重复计数、不重复发增量、不重复落库
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
        verify(valueOps, never()).increment(anyString(), anyLong());
        verify(likeRepository, never()).insert(any());
    }

    @Test
    void slotExists_doesNotQueryDb() {
        // 热路径：槽集合仍在 → 去重可信，绝不付灾备查询的代价
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);

        assertTrue(service.apply(POST_ID, 42L, true));

        verify(likeRepository, never()).existsLike(anyString(), anyLong());
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("1"), anyString());
    }

    @Test
    void slotMissingButDbEmpty_stillBumpsCount() {
        // 新帖首赞：集合不存在、灾备表也无记录 → 正常计数（不能因为加了兜底而漏计）
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        when(likeRepository.existsLike(POST_ID, 42L)).thenReturn(false);

        assertTrue(service.apply(POST_ID, 42L, true));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("1"), anyString());
        verify(likeRepository).insert(any());
    }

    @Test
    void dbLookupThrows_treatsAsNewLike() {
        // 灾备查询不可用 → fail-open 按新赞放行（= 兜底前的既有行为），绝不因兜底故障拒绝点赞
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        when(likeRepository.existsLike(POST_ID, 42L)).thenThrow(new RuntimeException("db down"));

        assertTrue(service.apply(POST_ID, 42L, true));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(COUNT_KEY)), eq("1"), anyString());
    }

    // ===== ①-b 冷启动校正：计数 Key 缺失时不得从 0 起算 =====

    @Test
    void countKeyMissing_coldStartRecalculatesFromSlots_insteadOfStartingFromZero() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        scriptReturn = null; // 脚本判定：计数 Key 原本不存在
        // 64 槽去重集合合计 500（含刚点赞的这条）
        pipelineResults.add(slotSums(1, 500));
        when(valueOps.setIfAbsent(eq(COUNT_KEY), eq("500"), any())).thenReturn(true);

        service.apply(POST_ID, 42L, true);

        // 关键：必须按去重集合权威值校正，否则已有 500 赞的帖子会显示 1
        verify(valueOps).setIfAbsent(eq(COUNT_KEY), eq("500"), any());
        verify(valueOps, never()).increment(eq(COUNT_KEY), eq(1L));
    }

    @Test
    void coldStart_concurrentInit_appliesDeltaInsteadOfOverwriting() {
        when(setOps.add(anyString(), eq("42"))).thenReturn(1L);
        scriptReturn = null;
        pipelineResults.add(slotSums(1, 500));
        // SETNX 失败 = 已被并发线程初始化 → 不得覆盖，改为在其之上补本次 delta
        when(valueOps.setIfAbsent(eq(COUNT_KEY), anyString(), any())).thenReturn(false);

        service.apply(POST_ID, 42L, true);

        verify(valueOps).increment(COUNT_KEY, 1L);
    }

    // ===== ② 读路径 O(1)：热命中不再扫 64 槽 =====

    @Test
    void likeCount_readsCountKeyOnce_withoutScanningSlots() {
        when(valueOps.get(COUNT_KEY)).thenReturn("128");

        assertEquals(128L, service.likeCount(POST_ID));

        // A1 核心收益：读一次点赞数只发 1 个 GET；pipeline 仅用于 A4 续期（EXPIRE），不得出现 SCARD
        verify(connection, never()).sCard(any(byte[].class));
    }

    @Test
    void likeCountBatch_warmHit_usesSingleMget_andNoPipelineAtAll() {
        when(valueOps.multiGet(anyList())).thenReturn(List.of("10", "20"));

        Map<String, Long> counts = service.likeCounts(List.of("p1", "p2"));

        assertEquals(10L, counts.get("p1"));
        assertEquals(20L, counts.get("p2"));
        verify(valueOps, times(1)).multiGet(anyList());
        // 热命中路径连一次 pipeline 都不该有（改造前是 N×64 个 SCARD）
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    // ===== ③ 冷启动回退：计数 Key 缺失时用 64 槽求和并预热 =====

    @Test
    void likeCount_fallsBackToSlots_whenCountKeyMissing() {
        when(valueOps.get(COUNT_KEY)).thenReturn(null);
        // pipeline #1 = A4 续期（结果被忽略），#2 = SCARD 求和
        pipelineResults.add(List.of());
        pipelineResults.add(List.of(3L, 4L, 0L));

        assertEquals(7L, service.likeCount(POST_ID), "计数 Key 缺失时应回退 64 槽 SCARD 求和");
    }

    @Test
    void likeCountBatch_coldStart_sumsSlotsAndWarmsUpWithinConstantRoundTrips() {
        // List.of 不允许 null 元素，冷启动缺失项必须用 Arrays.asList
        when(valueOps.multiGet(anyList())).thenReturn(java.util.Arrays.asList(null, null));
        // pipeline #1 = 全部 SCARD（2 项 × 64 槽，按 idx*64+slot 索引），#2 = EXPIRE 续期 + SET NX 预热
        pipelineResults.add(slotSums(2, 5, 9));
        pipelineResults.add(List.of());

        Map<String, Long> counts = service.likeCounts(List.of("p1", "p2"));

        assertEquals(5L, counts.get("p1"));
        assertEquals(9L, counts.get("p2"));
        // 关键：冷启动回退 + 预热写回必须合并为常数次往返，不得退化成「每项一次」
        verify(redisTemplate, times(2)).executePipelined(any(RedisCallback.class));
    }

    // ===== ④ A4 灾备重建 =====

    @Test
    void rebuildFromDb_restoresCountFromDisasterRecoveryTable() {
        when(likeRepository.listUserIds(POST_ID)).thenReturn(List.of(1001L, 1002L, 1003L));

        assertEquals(3L, service.rebuildFromDb(POST_ID));

        // 重建是权威覆盖，用 SET（非 SETNX）写计数 Key
        verify(valueOps).set(eq(COUNT_KEY), eq("3"), any());
        verify(likeRepository).listUserIds(POST_ID);
    }

    @Test
    void rebuildFromDb_emptyTable_clearsStaleCount() {
        when(likeRepository.listUserIds(POST_ID)).thenReturn(List.of());

        assertEquals(0L, service.rebuildFromDb(POST_ID));

        verify(redisTemplate).delete(COUNT_KEY);
    }

    // ===== ⑤ fail-open =====

    @Test
    void apply_redisFailureIsFailOpen_andDoesNotThrow() {
        when(setOps.add(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(assertDoesNotThrow(() -> service.apply(POST_ID, 42L, true)),
                "fail-open：按请求态兜底，不得把 Redis 异常抛给主流程");
    }
}
