package com.turbofeed.feedengine.interest;

import com.turbofeed.feedengine.timeline.FeedTimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存量标签倒排<b>分数回填</b>的回归防护。
 *
 * <p><b>为什么要锁这个</b>：回填是「一次性运维作业」，最容易出的错是<b>静默错</b>——
 * 少填（老帖依然搜不到）、多填（给已下架内容补出分数，搜索重新捞出违规内容）、
 * 或者给新建的 ZSET 键忘了 TTL（造出一个永不过期、下架也清不掉的索引）。
 * 这几种都不会报错，只能靠断言拦住。</p>
 */
@ExtendWith(MockitoExtension.class)
class TagScoreBackfillServiceTest {

    private static final String SET_PREFIX = "tf:tag:media:";
    private static final String ZSET_PREFIX = "tf:tag:media:z:";

    @Mock
    StringRedisTemplate redis;

    @Mock
    SetOperations<String, String> setOps;

    @Mock
    ZSetOperations<String, String> zSetOps;

    @Mock
    Cursor<String> cursor;

    @Mock
    FeedTimelineStore timelineStore;

    private TagScoreBackfillService service;

    @BeforeEach
    void setUp() {
        service = new TagScoreBackfillService(redis, timelineStore);
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    }

    /** 让扫描依次吐出给定的 key，随后结束。 */
    private void scanReturns(String... keys) {
        Boolean[] hasNext = new Boolean[keys.length + 1];
        for (int i = 0; i < keys.length; i++) {
            hasNext[i] = true;
        }
        hasNext[keys.length] = false;
        when(cursor.hasNext()).thenReturn(hasNext[0], java.util.Arrays.copyOfRange(hasNext, 1, hasNext.length));
        when(cursor.next()).thenReturn(keys[0], java.util.Arrays.copyOfRange(keys, 1, keys.length));
    }

    @Test
    @DisplayName("仅补 SET 有、ZSET 没有的成员，并按入流时刻写分数 + 补 TTL")
    void fillsOnlyMissingMembers() {
        scanReturns(SET_PREFIX + "java");
        when(setOps.members(SET_PREFIX + "java")).thenReturn(Set.of("post/1/a", "post/1/b"));
        // a 已有分数（值为 null 的族元要跳过，防止 mock 返回带 null 的脏元组）
        Set<ZSetOperations.TypedTuple<String>> existing = new HashSet<>();
        existing.add(ZSetOperations.TypedTuple.of("post/1/a", 1000d));
        existing.add(ZSetOperations.TypedTuple.of(null, 0d));
        when(zSetOps.rangeWithScores(ZSET_PREFIX + "java", 0, -1)).thenReturn(existing);
        when(timelineStore.inflowScoreOf("post/1/b")).thenReturn(2000L);
        when(redis.getExpire(ZSET_PREFIX + "java")).thenReturn(-1L);

        TagScoreBackfillService.BackfillReport report = service.backfill(false, 0);

        assertEquals(1, report.scannedTags());
        assertEquals(2, report.scannedMembers());
        assertEquals(1, report.existingScored());
        assertEquals(1, report.filled());
        assertEquals(0, report.unresolved());
        assertEquals(0, report.failedKeys());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor =
                ArgumentCaptor.forClass(Set.class);
        verify(zSetOps).add(eq(ZSET_PREFIX + "java"), captor.capture());
        List<String> added = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> t : captor.getValue()) {
            added.add(t.getValue() + "@" + t.getScore().longValue());
        }
        assertEquals(List.of("post/1/b@2000"), added);
        // 新建的键必须带过期时间，否则永久残留
        verify(redis).expire(eq(ZSET_PREFIX + "java"), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("dryRun：只统计不写入，且不碰 TTL")
    void dryRunNeverWrites() {
        scanReturns(SET_PREFIX + "java");
        when(setOps.members(SET_PREFIX + "java")).thenReturn(Set.of("post/1/b"));
        when(zSetOps.rangeWithScores(ZSET_PREFIX + "java", 0, -1)).thenReturn(Set.of());
        when(timelineStore.inflowScoreOf("post/1/b")).thenReturn(2000L);

        TagScoreBackfillService.BackfillReport report = service.backfill(true, 0);

        assertEquals(1, report.filled());
        assertTrue(report.dryRun(), "报告必须标明这是 dryRun");
        verify(zSetOps, never()).add(anyString(), any(Set.class));
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("解析不到入流时刻的成员计入 unresolved，且绝不写入")
    void unresolvedMembersAreSkipped() {
        scanReturns(SET_PREFIX + "java");
        when(setOps.members(SET_PREFIX + "java")).thenReturn(Set.of("post/9/gone"));
        when(zSetOps.rangeWithScores(ZSET_PREFIX + "java", 0, -1)).thenReturn(null);
        when(timelineStore.inflowScoreOf("post/9/gone")).thenReturn(null);

        TagScoreBackfillService.BackfillReport report = service.backfill(false, 0);

        assertEquals(1, report.unresolved());
        assertEquals(0, report.filled());
        verify(zSetOps, never()).add(anyString(), any(Set.class));
    }

    @Test
    @DisplayName("SCAN 会把有序索引键一并扫出来，必须排除，不能当成标签名再套一层")
    void ignoresOrderedIndexKeys() {
        scanReturns(ZSET_PREFIX + "java", SET_PREFIX + "java");
        when(setOps.members(SET_PREFIX + "java")).thenReturn(Set.of("post/3/x"));
        when(zSetOps.rangeWithScores(ZSET_PREFIX + "java", 0, -1)).thenReturn(Set.of());
        when(timelineStore.inflowScoreOf("post/3/x")).thenReturn(4000L);

        TagScoreBackfillService.BackfillReport report = service.backfill(true, 0);

        // 只有真正的 SET 键被计为一个标签（ZSET 键若被当成标签，这里会是 2）
        assertEquals(1, report.scannedTags());
        assertEquals(1, report.filled());
        verify(setOps, times(1)).members(anyString());
    }

    @Test
    @DisplayName("单个标签失败不中断整体，且 rest 标签继续处理（failedKeys 可观测）")
    void singleTagFailureDoesNotAbortRun() {
        scanReturns(SET_PREFIX + "broken", SET_PREFIX + "ok");
        when(setOps.members(SET_PREFIX + "broken")).thenThrow(new RuntimeException("redis timeout"));
        when(setOps.members(SET_PREFIX + "ok")).thenReturn(Set.of("post/2/c"));
        when(zSetOps.rangeWithScores(ZSET_PREFIX + "ok", 0, -1)).thenReturn(Set.of());
        when(timelineStore.inflowScoreOf("post/2/c")).thenReturn(3000L);
        when(redis.getExpire(ZSET_PREFIX + "ok")).thenReturn(3600L);

        TagScoreBackfillService.BackfillReport report = service.backfill(false, 0);

        assertEquals(1, report.failedKeys());
        assertEquals(1, report.filled());
        // 已有 TTL 的键不再续期，避免回填把过期时间无限延长
        verify(redis, never()).expire(eq(ZSET_PREFIX + "ok"), any(Duration.class));
    }
}
