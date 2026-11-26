package com.turbofeed.gateway.service.share;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 短链 BF 单测（方案 §4.8 口径）：双哈希位置分布、零假阴性（位数组模拟）、
 * Redis 异常 fail-open、shadow 模式不拦截。
 */
class ShareBloomFilterTest {

    @Test
    void hashProducesCorrectCountAndRange() {
        String[] pos = ShareBloomFilter.positions("abcdefghijk");
        assertEquals(ShareBloomFilter.HASH_COUNT, pos.length, "哈希次数应为 k");
        for (String p : pos) {
            long bit = Long.parseLong(p);
            assertTrue(bit >= 0 && bit < ShareBloomFilter.BIT_SIZE, "bit 位置应在 [0,m) 内");
        }
        assertArrayEquals(pos, ShareBloomFilter.positions("abcdefghijk"), "同 code 位置应稳定");
        assertFalse(java.util.Arrays.equals(pos, ShareBloomFilter.positions("othercode123")),
                "不同 code 位置应不同");
    }

    @Test
    void bloomParametersMatchExpected() {
        // n=1e7 / p=1e-4 → m≈1.92e8, k=13
        assertEquals(13, ShareBloomFilter.HASH_COUNT, "哈希次数应为 13");
        assertTrue(ShareBloomFilter.BIT_SIZE >= 190_000_000L
                && ShareBloomFilter.BIT_SIZE <= 195_000_000L, "位图规模应约 1.92e8");
    }

    @Test
    void shouldRejectFailsOpenOnRedisError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        ShareBloomFilter bf = new ShareBloomFilter(redis, new SimpleMeterRegistry(), true);

        assertFalse(bf.shouldReject("anycode"), "Redis 故障时 BF 必须 fail-open，不能让全部短链 404");
    }

    @Test
    void addDoesNotThrowOnRedisError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        ShareBloomFilter bf = new ShareBloomFilter(redis, new SimpleMeterRegistry(), false);

        assertDoesNotThrow(() -> bf.add("code123"), "BF 置位失败不能回滚主流程");
    }

    @Test
    void zeroFalseNegativeWithInMemoryBitmap() {
        // 用真实算法算位置，位数组模拟 SETBIT，验证"加入过的 code 全判可能存在"（零假阴性）
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        java.util.Set<Long> bits = new java.util.HashSet<>();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
            Object[] args = inv.getArgument(2);
            RedisScript<Long> script = inv.getArgument(0);
            String src = script.getScriptAsString();
            if (src.contains("SETBIT")) {
                for (Object a : args) bits.add(Long.parseLong(a.toString()));
                return 1L;
            }
            for (Object a : args) {
                if (!bits.contains(Long.parseLong(a.toString()))) return 0L;
            }
            return 1L;
        });
        ShareBloomFilter bf = new ShareBloomFilter(redis, new SimpleMeterRegistry(), true);

        for (int i = 0; i < 1000; i++) bf.add("code-" + i);
        for (int i = 0; i < 1000; i++) {
            assertFalse(bf.shouldReject("code-" + i), "已加入的 code 不得被判不存在（零假阴性）");
        }
    }
}
