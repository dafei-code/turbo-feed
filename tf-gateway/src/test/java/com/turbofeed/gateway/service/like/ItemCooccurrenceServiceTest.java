package com.turbofeed.gateway.service.like;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ItemCooccurrenceService} 单测（纯 Mockito，无中间件）。
 * 覆盖增量对称维护、衰减归零移除、批重建覆盖写、fail-open。
 * 注：{@code max-seeds/max-neighbors/favorite-weight} 为 {@code @Value} 字段，单元测试无 Spring 注入，
 * 用反射设置默认值。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ItemCooccurrenceServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock MediaFavoriteJdbcRepository favoriteRepository;
    @Mock RedisConnection connection;
    @Mock RedisKeyCommands keyCommands;

    private ItemCooccurrenceService service;

    @BeforeEach
    void setUp() throws Exception {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(inv -> {
            ((RedisCallback<?>) inv.getArgument(0)).doInRedis(connection);
            return List.of();
        });
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(keyCommands.expire(any(byte[].class), anyLong())).thenReturn(true);
        when(connection.zAdd(any(byte[].class), anyDouble(), any(byte[].class))).thenReturn(true);
        when(zSetOps.zCard(anyString())).thenReturn(2L); // 默认不触发 trim

        service = new ItemCooccurrenceService(redisTemplate, favoriteRepository);
        setField("maxSeeds", 50);
        setField("maxNeighbors", 500);
        setField("favoriteWeight", 1.5);
    }

    private void setField(String name, Object val) throws Exception {
        Field f = ItemCooccurrenceService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, val);
    }

    @Test
    void onFavorite_bumpsSymmetricPairs() {
        when(zSetOps.reverseRange("tf:favorite:user:42", 0, 49)).thenReturn(Set.of("A", "B"));

        service.onFavorite("A", 42L);

        // A 与 B 双向 +1.5（A 自身被跳过）
        verify(zSetOps).incrementScore("tf:cf:item:A", "B", 1.5);
        verify(zSetOps).incrementScore("tf:cf:item:B", "A", 1.5);
        verify(redisTemplate, times(2)).expire(anyString(), any());
    }

    @Test
    void onUnfavorite_decaysSymmetricAndRemovesAtZero() {
        when(zSetOps.reverseRange("tf:favorite:user:42", 0, 49)).thenReturn(Set.of("B"));
        when(zSetOps.incrementScore("tf:cf:item:A", "B", -1.5)).thenReturn(0.0); // 归零 → 移除
        when(zSetOps.incrementScore("tf:cf:item:B", "A", -1.5)).thenReturn(0.5); // 未归零 → 保留

        service.onUnfavorite("A", 42L);

        verify(zSetOps).incrementScore("tf:cf:item:A", "B", -1.5);
        verify(zSetOps).incrementScore("tf:cf:item:B", "A", -1.5);
        verify(zSetOps).remove("tf:cf:item:A", "B");
        verify(zSetOps, never()).remove("tf:cf:item:B", "A");
    }

    @Test
    void onFavorite_failOpenOnRedisError() {
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong())).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> service.onFavorite("A", 42L));
    }

    @Test
    void rebuildAll_overwritesCooccurrenceFromDb() {
        when(favoriteRepository.listAllUserPostPairs()).thenReturn(List.of(
                new MediaFavoriteJdbcRepository.FavoritePair(42L, "A"),
                new MediaFavoriteJdbcRepository.FavoritePair(42L, "B"),
                new MediaFavoriteJdbcRepository.FavoritePair(42L, "C")));

        service.rebuildAll();

        // 每篇内容先 DEL 旧键再 ZADD 邻居
        verify(redisTemplate).delete("tf:cf:item:A");
        verify(redisTemplate).delete("tf:cf:item:B");
        verify(redisTemplate).delete("tf:cf:item:C");
        // 共现 A-B, A-C, B-A, B-C, C-A, C-B = 6 条 ZADD
        verify(connection, times(6)).zAdd(any(byte[].class), anyDouble(), any(byte[].class));
    }

    @Test
    void rebuildAll_noPairsIsNoop() {
        when(favoriteRepository.listAllUserPostPairs()).thenReturn(List.of());

        service.rebuildAll();

        verify(redisTemplate, never()).delete(anyString());
    }
}
