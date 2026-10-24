package com.turbofeed.gateway.service.comment;

import com.turbofeed.gateway.util.SnowflakeIdGenerator;
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
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A2 回归护栏：评论点赞计数从「N×64 SCARD」改为「独立计数 Key + 一次 MGET」。
 *
 * <p>纯 Mockito、不启 Spring、不需要 Docker。锁定五类易回归行为：</p>
 * <ul>
 *   <li>① <b>写路径同步</b>：SADD/SREM 返回 1 才动计数 Key；重复 toggle 不重复计数。</li>
 *   <li>② <b>批量读 O(1)</b>（核心收益）：{@code likeCounts} 热命中走一次 {@code MGET}，
 *       不再触发 N×64 个 {@code SCARD}（改造前 20 条评论 = 1280 个 Redis 命令）。</li>
 *   <li>③ <b>冷启动回退</b>：计数 Key 缺失时回退 64 槽求和，且回退 + 续期 + 预热
 *       合并为常数次 pipeline，不得退化成「每条评论一次往返」。</li>
 *   <li>④ <b>A4 灾备重建</b>：{@code rebuildFromDb} 用 {@code comment_like} 表恢复计数。</li>
 *   <li>⑤ <b>fail-open</b>：Redis 异常按未赞/0 兜底，不抛给主流程。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentLikeServiceTest {

    private static final String MEDIA_ID = "media/42/x.jpg";
    private static final int SLOT_COUNT = 64;

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;
    @Mock CommentLikeJdbcRepository likeRepository;
    @Mock SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock ObjectProvider<com.turbofeed.gateway.service.counter.CounterEventPublisher> counterPublisher;
    @Mock RedisConnection connection;
    @Mock RedisKeyCommands keyCommands;
    @Mock RedisStringCommands stringCommands;
    @Mock RedisSetCommands setCommands;

    private final java.util.concurrent.Executor directExecutor = Runnable::run;
    private final Deque<List<Object>> pipelineResults = new ArrayDeque<>();

    /**
     * 计数脚本（{@code LikeCountScript.COUNT_BUMP}）的返回值：
     * 非 {@code null} = 热路径（Key 原本存在）；{@code null} = 冷启动（Key 原本不存在，须按 64 槽重算校正）。
     */
    private Long scriptReturn = 1L;

    private CommentLikeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
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
        // 计数维护脚本：A2 起热路径走脚本（INCRBY+PEXPIRE 一次往返），不再直接调 increment
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenAnswer(inv -> scriptReturn);
        service = new CommentLikeService(redisTemplate, likeRepository, snowflakeIdGenerator, counterPublisher);
        // interactionExecutor 是 @Autowired 字段注入，反射设入直接执行的 Executor
        try {
            var f = CommentLikeService.class.getDeclaredField("interactionExecutor");
            f.setAccessible(true);
            f.set(service, directExecutor);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 构造 N 项 × 64 槽的 SCARD 结果，使第 idx 项之和恰为 wantSum（放在 slot 0）。 */
    private static List<Object> slotSums(int items, long... wantSums) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            long want = i < wantSums.length ? wantSums[i] : 0L;
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                out.add(slot == 0 ? want : 0L);
            }
        }
        return out;
    }

    // ===== ① 写路径同步 =====

    @Test
    void firstLike_bumpsCountKeyViaScript() {
        when(setOps.add(anyString(), eq("7"))).thenReturn(1L);

        assertTrue(service.toggle(MEDIA_ID, 1001L, 7L));

        // A2 起计数维护走脚本（EXISTS + INCRBY + PEXPIRE 一次往返），不再直接调 increment
        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("tf:comment:like:count:1001")), eq("1"), anyString());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void toggleOff_bumpsCountKeyWithNegativeDelta() {
        when(setOps.add(anyString(), eq("7"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("7"))).thenReturn(1L);

        assertFalse(service.toggle(MEDIA_ID, 1001L, 7L));

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("tf:comment:like:count:1001")), eq("-1"), anyString());
    }

    @Test
    void toggleNoOpStateChange_doesNotTouchCountKey() {
        // SADD 返回 0（已赞）后 SREM 也返回 0（本就不在）→ 无状态变化
        when(setOps.add(anyString(), eq("7"))).thenReturn(0L);
        when(setOps.remove(anyString(), eq("7"))).thenReturn(0L);

        service.toggle(MEDIA_ID, 1001L, 7L);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    // ===== ①-b 冷启动校正：计数 Key 缺失时不得从 0 起算 =====

    @Test
    void countKeyMissing_coldStartRecalculatesFromSlots() {
        when(setOps.add(anyString(), eq("7"))).thenReturn(1L);
        scriptReturn = null; // 脚本判定：计数 Key 原本不存在
        pipelineResults.add(slotSums(1, 500)); // 64 槽去重集合合计 500（含刚点赞这条）
        when(valueOps.setIfAbsent(eq("tf:comment:like:count:1001"), eq("500"), any())).thenReturn(true);

        service.toggle(MEDIA_ID, 1001L, 7L);

        // 关键：必须按去重集合权威值校正，否则已有 500 赞的评论会显示 1
        verify(valueOps).setIfAbsent(eq("tf:comment:like:count:1001"), eq("500"), any());
        verify(valueOps, never()).increment(eq("tf:comment:like:count:1001"), eq(1L));
    }

    @Test
    void coldStart_concurrentInit_appliesDeltaInsteadOfOverwriting() {
        when(setOps.add(anyString(), eq("7"))).thenReturn(1L);
        scriptReturn = null;
        pipelineResults.add(slotSums(1, 500));
        // SETNX 失败 = 已被并发线程初始化 → 不得覆盖，改为在其之上补本次 delta
        when(valueOps.setIfAbsent(eq("tf:comment:like:count:1001"), anyString(), any())).thenReturn(false);

        service.toggle(MEDIA_ID, 1001L, 7L);

        verify(valueOps).increment("tf:comment:like:count:1001", 1L);
    }

    // ===== ② 批量读 O(1)：热命中走一次 MGET =====

    @Test
    void likeCounts_warmHit_usesSingleMget_andNoSlotScan() {
        when(valueOps.multiGet(anyList())).thenReturn(List.of("10", "20", "30"));

        Map<Long, Long> counts = service.likeCounts(List.of(1L, 2L, 3L));

        assertEquals(10L, counts.get(1L));
        assertEquals(20L, counts.get(2L));
        assertEquals(30L, counts.get(3L));
        verify(valueOps, times(1)).multiGet(anyList());
        // A2 核心收益：热命中路径连一次 pipeline 都不该有（改造前是 3×64=192 个 SCARD）
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void likeCount_single_readsCountKeyWithoutSlotScan() {
        when(valueOps.get("tf:comment:like:count:1001")).thenReturn("55");

        assertEquals(55L, service.likeCount("1001"));
        // 单条读的 pipeline 仅用于 A4 续期，不得出现 SCARD
        verify(connection, never()).sCard(any(byte[].class));
    }

    // ===== ③ 冷启动回退 =====

    @Test
    void likeCounts_coldStart_sumsSlotsAndWarmsUpWithinConstantRoundTrips() {
        when(valueOps.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
        // pipeline #1 = 全部 SCARD（2 项 × 64 槽），#2 = EXPIRE 续期 + SET NX 预热
        pipelineResults.add(slotSums(2, 5, 9));
        pipelineResults.add(List.of());

        Map<Long, Long> counts = service.likeCounts(List.of(1L, 2L));

        assertEquals(5L, counts.get(1L));
        assertEquals(9L, counts.get(2L));
        // 回退 + 续期 + 预热合并为常数次往返，不退化成「每条评论一次」
        verify(redisTemplate, times(2)).executePipelined(any(RedisCallback.class));
    }

    @Test
    void likeCount_coldStart_fallsBackToSlots() {
        when(valueOps.get("tf:comment:like:count:1001")).thenReturn(null);
        // #1 = A4 续期（忽略），#2 = SCARD 求和
        pipelineResults.add(List.of());
        pipelineResults.add(List.of(4L, 3L, 0L));

        assertEquals(7L, service.likeCount("1001"));
    }

    // ===== ④ A4 灾备重建 =====

    @Test
    void rebuildFromDb_restoresCount() {
        when(likeRepository.listUserIds(MEDIA_ID, 1001L)).thenReturn(List.of(11L, 12L));

        assertEquals(2L, service.rebuildFromDb(MEDIA_ID, 1001L));
        verify(valueOps).set(eq("tf:comment:like:count:1001"), eq("2"), any());
    }

    @Test
    void recountFromSlots_overwritesCountKey() {
        pipelineResults.add(slotSums(1, 8));

        assertEquals(8L, service.recountFromSlots("1001"));
        verify(valueOps).set(eq("tf:comment:like:count:1001"), eq("8"), any());
    }

    // ===== ⑤ fail-open =====

    @Test
    void toggle_redisFailureIsFailOpen() {
        when(setOps.add(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));
        when(setOps.isMember(anyString(), anyString())).thenReturn(false);

        assertFalse(assertDoesNotThrow(() -> service.toggle(MEDIA_ID, 1001L, 7L)),
                "fail-open：Redis 异常按查询态兜底，不抛给主流程");
    }

    @Test
    void likeCounts_redisFailure_returnsEmptyMap() {
        when(valueOps.multiGet(anyList())).thenThrow(new RuntimeException("redis down"));

        assertTrue(service.likeCounts(List.of(1L, 2L)).isEmpty(),
                "批量读失败应返回空 Map，由调用方按 DB 冗余值兜底");
    }
}
