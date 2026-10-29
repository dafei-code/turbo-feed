package com.turbofeed.feedengine.timeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 收藏信号进入内容实时分 / 热点榜的回归护栏：
 * {@code FAVORITE} 累加 {@code favorites} 字段且热点权重 2.0；{@code UNFAVORITE} 镜像回退。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostStatServiceFavoriteTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock HashOperations<String, Object, Object> hashOps;
    @Mock SetOperations<String, String> setOps;
    @Mock ZSetOperations<String, String> zSetOps;

    private PostStatService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);
        service = new PostStatService(redisTemplate);
    }

    @Test
    void record_favorite_incrementsFavoritesField_andHotWeight2() {
        service.record("k", "FAVORITE");

        verify(hashOps).increment(anyString(), eq("favorites"), eq(1L));
        // HOT_WEIGHTS[FAVORITE] = 2.0
        verify(zSetOps).incrementScore(anyString(), eq("k"), eq(2.0));
    }

    @Test
    void cancel_unfavorite_decrementsFavoritesField_andHotWeightNegative2() {
        service.cancel("k", "UNFAVORITE");

        // toField(UNFAVORITE) -> favorites；cancel 回退 -1
        verify(hashOps).increment(anyString(), eq("favorites"), eq(-1L));
        // hotWeightOf(UNFAVORITE) -> FAVORITE -> 2.0，cancel 取负 -2.0
        verify(zSetOps).incrementScore(anyString(), eq("k"), eq(-2.0));
    }

    @Test
    void snapshot_readsFavoritesField() {
        Map<Object, Object> m = new HashMap<>();
        m.put("favorites", 7L);
        when(hashOps.entries(anyString())).thenReturn(m);

        PostStatService.PostStat s = service.snapshot("k");

        assertEquals(7L, s.favorites());
    }
}
