package com.turbofeed.gateway.service.comment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A8 回归护栏：评论总数计数的冷启动校正与滑动续期。
 *
 * <p>纯 Mockito、不启 Spring、不需要 Docker。锁定本方案修复的<b>真实缺陷</b>：</p>
 * <ul>
 *   <li>① <b>冷启动校正</b>（核心）：Redis Key 缺失时 {@code INCR} 返回 1，必须以 DB 真实值补齐差额。
 *       改造前直接 {@code INCR} 会让「上千条评论的内容」在 Key 过期后从 1 重新累加，
 *       且因 Key 已存在，{@code getCount} 的 DB 回退永不触发 → 长期显示个位数。</li>
 *   <li>② <b>非冷启动不校正</b>：{@code INCR} 返回 &gt;1（Key 已存在）时不得触发 {@code COUNT(*)}，
 *       否则每条评论都扫一次分片库，抵消「避开 COUNT(*)」的设计初衷。</li>
 *   <li>③ <b>读路径</b>：命中直接返回；缺失回退 DB 并预热。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentCountServiceTest {

    private static final String MEDIA_ID = "media/42/x.jpg";
    private static final String COUNT_KEY = "tf:comment:count:" + MEDIA_ID;

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock CommentJdbcRepository commentRepository;

    private CommentCountService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new CommentCountService(redisTemplate, commentRepository);
    }

    // ===== ① 冷启动校正（本方案修复的真实缺陷）=====

    @Test
    void incr_coldStart_correctsCountFromDb() {
        // INCR 返回 1 = Key 为本次新建（Redis 重启 / TTL 过期后的第一条评论）
        when(valueOps.increment(COUNT_KEY)).thenReturn(1L);
        when(commentRepository.countAll(MEDIA_ID)).thenReturn(1000L);

        service.incr(MEDIA_ID);

        // 必须以 DB 真实值补齐差额，否则总数从 1 重新累加
        verify(valueOps).increment(COUNT_KEY, 1000L);
        verify(commentRepository).countAll(MEDIA_ID);
    }

    @Test
    void incr_coldStart_emptyDb_doesNotOverCorrect() {
        when(valueOps.increment(COUNT_KEY)).thenReturn(1L);
        when(commentRepository.countAll(MEDIA_ID)).thenReturn(0L);

        service.incr(MEDIA_ID);

        // DB 为 0（全新内容）：当前这条就是第 1 条，无需补差额
        verify(valueOps, never()).increment(eq(COUNT_KEY), anyLong());
    }

    // ===== ② 非冷启动不触发 COUNT(*) =====

    @Test
    void incr_warmKey_doesNotQueryDb() {
        // INCR 返回 >1 = Key 已存在，是热路径的常规自增
        when(valueOps.increment(COUNT_KEY)).thenReturn(5L);

        service.incr(MEDIA_ID);

        verify(commentRepository, never()).countAll(anyString());
        verify(valueOps, never()).increment(eq(COUNT_KEY), anyLong());
    }

    // ===== ③ 读路径 =====

    @Test
    void getCount_cacheHit_returnsDirectly() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(Arrays.asList((Object) "42"));

        assertEquals(42L, service.getCount(MEDIA_ID));
        verify(commentRepository, never()).countAll(anyString());
    }

    @Test
    void getCount_cacheMiss_fallsBackToDbAndWarmsUp() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(Arrays.asList((Object) null));
        when(commentRepository.countAll(MEDIA_ID)).thenReturn(7L);

        assertEquals(7L, service.getCount(MEDIA_ID));
        // 回退 DB 后应预热写回（A4 滑动续期的一部分）
        verify(valueOps).set(eq(COUNT_KEY), eq("7"), any());
    }

    @Test
    void getCount_redisFailure_fallsBackToDb() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("redis down"));
        when(commentRepository.countAll(MEDIA_ID)).thenReturn(3L);

        assertEquals(3L, service.getCount(MEDIA_ID), "Redis 异常必须 fail-open 回退 DB，不得抛给调用方");
    }
}
