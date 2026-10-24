package com.turbofeed.gateway.service.comment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A3 回归护栏：评论热头写入从「4 次独立往返」合并为「1 次 pipeline」。
 *
 * <p>纯 Mockito、不启 Spring、不需要 Docker。锁定：</p>
 * <ul>
 *   <li>① <b>单次往返</b>：{@code add} 内 4 条命令（ZADD recent + ZADD hot + EXPIRE×2）
 *       在<b>一个</b> pipeline 内发出——大并发发布评论时往返开销从 ×4 降到 ×1。</li>
 *   <li>② <b>TTL 不丢</b>：两个 ZSET 都必须带 EXPIRE，否则会留下无 TTL 的永久 Key（内存泄漏面）。</li>
 *   <li>③ <b>fail-open</b>：Redis 异常只告警，不得把失败抛给评论发布主流程。</li>
 *   <li>④ <b>读路径</b>：hotHead/recentHead 正常返回；异常回退空集。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentHotServiceTest {

    private static final String MEDIA_ID = "media/42/x.jpg";

    @Mock StringRedisTemplate redisTemplate;
    @Mock ZSetOperations<String, String> zsetOps;
    @Mock RedisConnection connection;
    @Mock RedisZSetCommands zSetCommands;
    @Mock RedisKeyCommands keyCommands;

    private CommentHotService service;

    private static Comment comment(long id) {
        return new Comment(id, MEDIA_ID, 7L, 0L, 0L, "内容", CommentStatus.APPROVED, 0,
                Instant.parse("2026-10-23T00:00:00Z"));
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        when(connection.zSetCommands()).thenReturn(zSetCommands);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback<?> cb = inv.getArgument(0);
            cb.doInRedis(connection);
            return List.of();
        });
        service = new CommentHotService(redisTemplate);
    }

    // ===== ① 单次往返 =====

    @Test
    void add_issuesAllFourCommandsInOnePipeline() {
        service.add(comment(1001L));

        // A3 核心：4 条命令合并为 1 次 pipeline（改造前是 4 次独立往返）
        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));
        verify(zSetCommands, times(2)).zAdd(any(byte[].class), anyDouble(), any(byte[].class));
        // ② 两个 ZSET 都必须带 TTL，否则留下无 TTL 的永久 Key
        verify(keyCommands, times(2)).expire(any(byte[].class), anyLong());
    }

    @Test
    void onLike_usesSingleIncrement() {
        service.onLike(1001L, MEDIA_ID, 1);

        verify(zsetOps).incrementScore(eq("tf:comment:hot:" + MEDIA_ID), eq("1001"), eq(1.0));
    }

    // ===== ③ fail-open =====

    @Test
    void add_redisFailureIsFailOpen() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> service.add(comment(1001L)),
                "热头写入失败必须 fail-open，不得影响评论发布主流程");
    }

    // ===== ④ 读路径 =====

    @Test
    void hotHead_returnsIdsFromZset() {
        when(zsetOps.reverseRange(eq("tf:comment:hot:" + MEDIA_ID), eq(0L), eq(9L)))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("1001", "1002")));

        List<Long> ids = service.hotHead(MEDIA_ID, 10);

        assertEquals(List.of(1001L, 1002L), ids);
    }

    @Test
    void hotHead_redisFailure_returnsEmpty() {
        when(zsetOps.reverseRange(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        assertTrue(service.hotHead(MEDIA_ID, 10).isEmpty(), "读失败应回退空集，不得抛给调用方");
    }
}
