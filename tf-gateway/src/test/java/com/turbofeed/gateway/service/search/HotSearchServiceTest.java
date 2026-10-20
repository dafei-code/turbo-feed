package com.turbofeed.gateway.service.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 热搜榜的回归防护。
 *
 * <p><b>为什么要锁这个</b>：热搜有三类不易察觉的错误——① 去重失效（一个用户能把任意词刷上榜首）；
 * ② 跨天拼接错误（当天的桶写进去、读的时候读错了日期，榜单永远是空的）；③ 排序抖动
 * （同分时顺序不稳定，前端每次刷新都跳）。三者都不会抛异常。</p>
 */
@ExtendWith(MockitoExtension.class)
class HotSearchServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> valueOps;

    @Mock
    ZSetOperations<String, String> zSetOps;

    private HotSearchService service;

    @BeforeEach
    void setUp() {
        service = new HotSearchService(redis);
    }

    private static String today() {
        return LocalDate.now(ZONE).format(DAY);
    }

    private static ZSetOperations.TypedTuple<String> tuple(String word, double score) {
        return ZSetOperations.TypedTuple.of(word, score);
    }

    @Test
    @DisplayName("首次搜索：同人同词去重放行，ZINCRBY +1 并续上日桶 TTL")
    void recordsFirstQuery() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        service.record("1001", "#Java");

        // 词按搜索口径归一化（去 #、小写），否则榜单上的词点进去搜不到
        String hotKey = HotSearchService.HOT_PREFIX + today();
        verify(zSetOps).incrementScore(hotKey, "java", 1d);
        verify(redis).expire(eq(hotKey), any(Duration.class));
    }

    @Test
    @DisplayName("60s 内同人同词第二次：去重命中，不计数")
    void dedupBlocksRepeatedQuery() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        service.record("1001", "java");

        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("空词与超长词不进榜（噪声 / 攻击载荷）")
    void ignoresBlankAndOverlongWords() {
        service.record("1001", "   ");
        service.record("1001", "x".repeat(65));

        verify(redis, never()).opsForValue();
        verify(redis, never()).opsForZSet();
    }

    @Test
    @DisplayName("榜单跨天合并：同词分数累加，按热度降序，同分按词序稳定")
    void topMergesWindow() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        LocalDate today = LocalDate.now(ZONE);
        String k0 = HotSearchService.HOT_PREFIX + today.format(DAY);
        String k1 = HotSearchService.HOT_PREFIX + today.minusDays(1).format(DAY);

        Set<ZSetOperations.TypedTuple<String>> d0 = new LinkedHashSet<>(List.of(tuple("java", 3), tuple("ai", 1)));
        Set<ZSetOperations.TypedTuple<String>> d1 = new LinkedHashSet<>(List.of(tuple("java", 2), tuple("go", 1)));
        when(zSetOps.reverseRangeWithScores(k0, 0, 9)).thenReturn(d0);
        when(zSetOps.reverseRangeWithScores(k1, 0, 9)).thenReturn(d1);

        HotSearchResult result = service.top(10);

        assertEquals(3, result.items().size());
        assertEquals("java", result.items().get(0).word());
        assertEquals(5d, result.items().get(0).score());
        // ai 与 go 同为 1 分，按词字典序稳定输出
        assertEquals("ai", result.items().get(1).word());
        assertEquals("go", result.items().get(2).word());
    }

    @Test
    @DisplayName("limit 被钳到上限；某一天读失败不影响整体")
    void topClampsLimitAndToleratesDayFailure() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        LocalDate today = LocalDate.now(ZONE);
        String k0 = HotSearchService.HOT_PREFIX + today.format(DAY);
        String k1 = HotSearchService.HOT_PREFIX + today.minusDays(1).format(DAY);

        when(zSetOps.reverseRangeWithScores(k0, 0, 49)).thenThrow(new RuntimeException("redis timeout"));
        when(zSetOps.reverseRangeWithScores(k1, 0, 49))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 1))));

        HotSearchResult result = service.top(999);

        assertEquals(1, result.items().size());
        assertEquals("java", result.items().get(0).word());
    }

    @Test
    @DisplayName("窗口内无数据：返回空榜单而不是异常")
    void topReturnsEmptyWhenNoData() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(anyString(), eq(0), eq(9))).thenReturn(Set.of());

        HotSearchResult result = service.top(10);

        assertTrue(result.items().isEmpty());
    }
}
