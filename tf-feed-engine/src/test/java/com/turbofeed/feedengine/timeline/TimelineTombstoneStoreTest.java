package com.turbofeed.feedengine.timeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-D3 时间线墓碑组件单测（纯 Mockito，零中间件）。
 *
 * <p><b>最重要的两条护栏</b>：
 * <ol>
 *   <li>「APPEND 早于/等于墓碑 → 拦」与「APPEND 晚于墓碑 → 放行」必须同时成立——
 *       后者是申诉翻案后合法重新入流的通道，一刀切永久封杀会造成正常内容永远进不了公域；</li>
 *   <li>{@code occurredAt == null}（存量事件）必须零 Redis 流量且放行，
 *       否则上线当天所有存量重投都会被误判。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TimelineTombstoneStoreTest {

    private static final String KEY = "post/1/x";
    private static final String REDIS_KEY = TimelineTombstoneStore.KEY_PREFIX + KEY;
    private static final Instant APPEND_AT = Instant.parse("2026-11-16T08:00:00Z");
    private static final Instant REMOVE_AT = Instant.parse("2026-11-16T09:30:00Z");

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private TimelineTombstoneStore store(boolean enabled, long ttlDays) {
        return new TimelineTombstoneStore(redisTemplate, registry, enabled, ttlDays);
    }

    private void tombstoneIs(Instant at) {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenReturn(String.valueOf(at.toEpochMilli()));
    }

    // ==================== APPEND 判定 ====================

    @Test
    void staleAppend_olderThanTombstone_isBlockedAndCounted() {
        tombstoneIs(REMOVE_AT);

        assertTrue(store(true, 30).isStaleAppend(KEY, APPEND_AT));

        Counter counter = registry.find(TimelineTombstoneStore.METRIC_STALE_BLOCKED).counter();
        assertEquals(1.0, counter == null ? -1 : counter.count(), "拦截必须记指标，供验收核对");
    }

    @Test
    void staleAppend_equalToTombstone_isBlocked() {
        // ADR 判定口径是 <=（等于也拦），避免同毫秒的迟到 APPEND 钻空子
        tombstoneIs(REMOVE_AT);
        assertTrue(store(true, 30).isStaleAppend(KEY, REMOVE_AT));
    }

    @Test
    void newerAppend_afterTombstone_isAllowed() {
        // 申诉翻案 / 重新过审：产生时刻晚于下架，必须能重新入流
        tombstoneIs(REMOVE_AT);
        Instant reapplied = REMOVE_AT.plusSeconds(60);
        assertFalse(store(true, 30).isStaleAppend(KEY, reapplied));
        assertEquals(0.0, registry.find(TimelineTombstoneStore.METRIC_STALE_BLOCKED).counter() == null
                ? 0.0 : registry.find(TimelineTombstoneStore.METRIC_STALE_BLOCKED).counter().count());
    }

    @Test
    void noTombstone_isAllowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenReturn(null);
        assertFalse(store(true, 30).isStaleAppend(KEY, APPEND_AT));
    }

    @Test
    void legacyEvent_nullOccurredAt_isAllowedWithZeroRedisTraffic() {
        assertFalse(store(true, 30).isStaleAppend(KEY, null));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void disabled_switch_skipsReadAndWrite() {
        assertFalse(store(false, 30).isStaleAppend(KEY, APPEND_AT));
        assertDoesNotThrow(() -> store(false, 30).markRemoved(KEY, REMOVE_AT));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void malformedTombstoneValue_isToleratedAndAllowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenReturn("not-a-number");
        assertFalse(store(true, 30).isStaleAppend(KEY, APPEND_AT), "脏数据不能让全站停发");
    }

    @Test
    void redisReadFailure_failsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenThrow(new IllegalStateException("redis down"));
        assertFalse(store(true, 30).isStaleAppend(KEY, APPEND_AT));
    }

    // ==================== REMOVE 登记墓碑 ====================

    @Test
    void markRemoved_writesEpochMillisAndTtlThroughLua() {
        TimelineTombstoneStore s = store(true, 30);
        s.markRemoved(KEY, REMOVE_AT);

        // 只前进不回退由 Lua 保证；这里锁住参数：epoch 毫秒 + 30 天 TTL（毫秒）
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(REDIS_KEY)),
                eq(String.valueOf(REMOVE_AT.toEpochMilli())),
                eq(String.valueOf(30L * 24 * 60 * 60 * 1000)));
    }

    @Test
    void markRemoved_legacyEvent_doesNotWrite() {
        store(true, 30).markRemoved(KEY, null);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void markRemoved_redisFailure_doesNotPropagate() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));
        assertDoesNotThrow(() -> store(true, 30).markRemoved(KEY, REMOVE_AT),
                "墓碑是防线，绝不能让下架主流程因它失败而回滚");
    }

    @Test
    void nonPositiveTtl_fallsBackToThirtyDays() {
        store(true, 0).markRemoved(KEY, REMOVE_AT);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(REDIS_KEY)),
                eq(String.valueOf(REMOVE_AT.toEpochMilli())),
                eq(String.valueOf(30L * 24 * 60 * 60 * 1000)));
    }

    @Test
    void luaScript_onlyAdvancesMonotonicTombstone() {
        // 锁住「旧 REMOVE 重放不能把水位改小」——DLQ 乱序到达时这是唯一防线
        assertTrue(TimelineTombstoneStore.LUA_ADVANCE.contains("tonumber(ARGV[1]) > tonumber(cur)"));
        assertTrue(TimelineTombstoneStore.LUA_ADVANCE.contains("PSETEX"));
        assertTrue(store(true, 30).isEnabled());
        assertFalse(store(false, 30).isEnabled());
    }
}
