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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 热搜榜的回归防护（搜索维度 + 点击维度）。
 *
 * <p><b>为什么要锁这个</b>：热搜有几类不易察觉的错误——① 去重失效（一个用户能把任意词刷上榜首）；
 * ② 跨天拼接错误（写进当天、读的时候读错日期，榜单永远空）；③ 排序抖动（同分顺序不稳定，
 * 前端每次刷新都跳）；④ <b>点击与搜索串桶</b>（点击数记进搜索桶，原始计数失真，
 * 运营再也看不出一个词是「搜得多」还是「点得多」）。</p>
 */
@ExtendWith(MockitoExtension.class)
class HotSearchServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final double W = 3.0;

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> valueOps;

    @Mock
    ZSetOperations<String, String> zSetOps;

    private HotSearchService service;

    @BeforeEach
    void setUp() {
        service = new HotSearchService(redis, W);
    }

    private static String today() {
        return LocalDate.now(ZONE).format(DAY);
    }

    private static String yesterday() {
        return LocalDate.now(ZONE).minusDays(1).format(DAY);
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

        verify(redis, never()).opsForZSet();
    }

    @Test
    @DisplayName("点击走独立日桶，不与搜索混在一起")
    void clickUsesSeparateBucket() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        service.recordClick("1001", "java");

        verify(zSetOps).incrementScore(HotSearchService.CLICK_PREFIX + today(), "java", 1d);
        // 点击绝不能记进搜索桶：否则原始计数失真，看不出「搜得多」还是「点得多」
        verify(zSetOps, never()).incrementScore(HotSearchService.HOT_PREFIX + today(), "java", 1d);
    }

    @Test
    @DisplayName("点击同样受同人同词 60s 去重保护")
    void clickIsDeduped() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        service.recordClick("1001", "java");

        verify(redis, never()).opsForZSet();
    }

    @Test
    @DisplayName("空词与超长词不进榜（噪声 / 攻击载荷）")
    void ignoresBlankAndOverlongWords() {
        service.record("1001", "   ");
        service.recordClick("1001", "x".repeat(65));

        verify(redis, never()).opsForValue();
        verify(redis, never()).opsForZSet();
    }

    @Test
    @DisplayName("榜单综合分 = 搜索次数 + 点击次数 × 权重，且原始计数一并返回")
    void topBlendsSearchAndClick() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(HotSearchService.HOT_PREFIX + today(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 2))));
        when(zSetOps.reverseRangeWithScores(HotSearchService.CLICK_PREFIX + today(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 1))));

        HotSearchResult result = service.top(10);

        assertEquals(1, result.items().size());
        HotSearchResult.Item it = result.items().get(0);
        assertEquals("java", it.word());
        assertEquals(2d, it.searches());
        assertEquals(1d, it.clicks());
        assertEquals(2d + 1d * W, it.score());
    }

    @Test
    @DisplayName("只被点击、从没被搜索的词也要能上榜（联想词直点）")
    void clickOnlyWordStillRanks() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(HotSearchService.HOT_PREFIX + today(), 0, 9)).thenReturn(Set.of());
        when(zSetOps.reverseRangeWithScores(HotSearchService.CLICK_PREFIX + today(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("ai", 2))));

        HotSearchResult result = service.top(10);

        assertEquals(1, result.items().size());
        assertEquals("ai", result.items().get(0).word());
        assertEquals(0d, result.items().get(0).searches());
        assertEquals(2 * W, result.items().get(0).score());
    }

    @Test
    @DisplayName("权重可配：改权重只改排序分，不改原始计数（含置 0 = 纯搜索量榜单）")
    void weightOnlyAffectsScore() {
        // 置 0 是运维「临时关掉点击加权」的合法配置，不能被静默改成默认值
        service = new HotSearchService(redis, 0.0);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(HotSearchService.HOT_PREFIX + today(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 2))));
        when(zSetOps.reverseRangeWithScores(HotSearchService.CLICK_PREFIX + today(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 5))));

        HotSearchResult result = service.top(10);

        assertEquals(2d, result.items().get(0).searches());
        assertEquals(5d, result.items().get(0).clicks());
        assertEquals(2d, result.items().get(0).score());
    }

    @Test
    @DisplayName("非法权重（负数）回退默认 3.0——不能让配置把点击词压到榜底")
    void negativeWeightFallsBackToDefault() {
        service = new HotSearchService(redis, -1.0);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(HotSearchService.HOT_PREFIX + today(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 2))));
        when(zSetOps.reverseRangeWithScores(HotSearchService.CLICK_PREFIX + today(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 5))));

        HotSearchResult result = service.top(10);

        // 2 + 5*3 = 17：负权重若被采纳会变成 -3，被点得越多的词反而排越后
        assertEquals(2d + 5d * W, result.items().get(0).score());
    }

    @Test
    @DisplayName("榜单跨天合并：同词分数累加，按热度降序，同分按词序稳定")
    void topMergesWindow() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(HotSearchService.HOT_PREFIX + today(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 3), tuple("ai", 1))));
        when(zSetOps.reverseRangeWithScores(HotSearchService.HOT_PREFIX + yesterday(), 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("java", 2), tuple("go", 1))));

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
        when(zSetOps.reverseRangeWithScores(HotSearchService.HOT_PREFIX + today(), 0, 49))
                .thenThrow(new RuntimeException("redis timeout"));
        when(zSetOps.reverseRangeWithScores(HotSearchService.HOT_PREFIX + yesterday(), 0, 49))
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
