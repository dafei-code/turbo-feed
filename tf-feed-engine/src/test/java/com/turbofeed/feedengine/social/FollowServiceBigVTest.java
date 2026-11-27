package com.turbofeed.feedengine.social;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0 大 V 优先扫描单测。
 *
 * <p>覆盖三件事：① 开关关闭时与改造前逐项一致（零回归）；② 开启后大 V 排到前面且组内保持
 * 关注时刻顺序，超出 limit 的小作者被截掉；③ pipeline HGET 失败时 fail-open 退回原顺序。</p>
 */
@ExtendWith(MockitoExtension.class)
class FollowServiceBigVTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ZSetOperations<String, String> zSetOps;

    private FollowService service(boolean enabled, long threshold, int window) {
        FollowService s = new FollowService(redisTemplate);
        ReflectionTestUtils.setField(s, "bigvFirstEnabled", enabled);
        ReflectionTestUtils.setField(s, "bigvFollowerThreshold", threshold);
        ReflectionTestUtils.setField(s, "bigvScanWindow", window);
        return s;
    }

    @Test
    void disabled_keepsOriginalFollowTimeOrder() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("a", "b", "c")));

        FollowService.RankedFollowees r = service(false, 100L, 200).rankedFollowees("u1", 50);

        assertEquals(List.of("a", "b", "c"), r.authors());
        assertTrue(r.bigVAuthors().isEmpty());
        verify(zSetOps).reverseRange("tf:follow:u1", 0, 49); // 未走扩大窗口
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void enabled_bigVFirstAndTruncateTail() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // 窗口 5：a(小) b(大V) c(小) d(大V) e(小)
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("a", "b", "c", "d", "e")));
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of(
                        "10".getBytes(StandardCharsets.UTF_8),
                        "200000".getBytes(StandardCharsets.UTF_8),
                        "20".getBytes(StandardCharsets.UTF_8),
                        "500000".getBytes(StandardCharsets.UTF_8),
                        "30".getBytes(StandardCharsets.UTF_8)));

        FollowService.RankedFollowees r = service(true, 100000L, 200).rankedFollowees("u1", 3);

        // 大 V b/d 在前（组内保序），普通作者 a 补位；c/e 被截掉
        assertEquals(List.of("b", "d", "a"), r.authors());
        assertEquals(Set.of("b", "d"), r.bigVAuthors());
        verify(zSetOps).reverseRange("tf:follow:u1", 0, 199); // 走扩大窗口
    }

    @Test
    void enabled_pipelineFailure_fallsBackToOriginalOrder() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("a", "b", "c")));
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("redis down"));

        FollowService.RankedFollowees r = service(true, 100000L, 200).rankedFollowees("u1", 50);

        assertEquals(List.of("a", "b", "c"), r.authors());
        assertTrue(r.bigVAuthors().isEmpty());
    }

    @Test
    void enabled_missingFollowers_treatedAsZero() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("a", "b")));
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(Arrays.asList(null, "150000".getBytes(StandardCharsets.UTF_8)));

        FollowService.RankedFollowees r = service(true, 100000L, 200).rankedFollowees("u1", 50);

        assertEquals(List.of("b", "a"), r.authors());
        assertEquals(Set.of("b"), r.bigVAuthors());
    }
}
