package com.turbofeed.gateway.service.like;

import com.turbofeed.gateway.service.feed.BehaviorEventPublisher;
import com.turbofeed.gateway.service.notify.NotificationGenerator;
import com.turbofeed.gateway.service.ratelimit.UploadRateLimiter;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.gateway.service.counter.CounterEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评审优化项③：热点计数分槽守护单测（纯 Mockito，无 Redis）。
 *
 * <p>重点不是「调没调 Redis」，而是索引对齐与迁移语义——{@code base + Σ64 槽} 一旦错位，
 * 表现就是点赞数悄悄多算/少算，且不会有任何异常。</p>
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class ShardedLikeCountStoreTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

    private ShardedLikeCountStore store(boolean enabled) {
        return new ShardedLikeCountStore(redis, enabled);
    }

    private void stubValueOps() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void disabledByDefault_zeroRegression() {
        assertFalse(store(false).isEnabled());
        assertTrue(store(true).isEnabled());
    }

    @Test
    void aggregate_basePlusAllSlots_withMixedTypesAndNulls() {
        List<Object> row = new ArrayList<>();
        row.add("500");                                   // base：开启分槽前的旧快照
        row.add(3L);                                     // slot 0
        row.add("2");                                    // slot 1
        row.add("-1".getBytes(StandardCharsets.UTF_8));  // slot 2：取消赞可为负
        row.add(null);                                   // slot 3：无数据
        for (int s = 4; s < ShardedLikeCountStore.SLOTS; s++) {
            row.add("0");
        }
        assertEquals(504L, ShardedLikeCountStore.aggregate(row, 0));
        // 列表页第二项的偏移必须是 SLOTS+1，否则跨项串算
        List<Object> two = new ArrayList<>(row);
        two.addAll(row);
        assertEquals(504L, ShardedLikeCountStore.aggregate(two, ShardedLikeCountStore.SLOTS + 1));
    }

    @Test
    void aggregate_toleratesShortResultList() {
        assertEquals(0L, ShardedLikeCountStore.aggregate(null, 0));
        assertEquals(7L, ShardedLikeCountStore.aggregate(List.of("7"), 0));
    }

    @Test
    void slotOf_isStableAndWithinRange() {
        int a = ShardedLikeCountStore.slotOf(123456789L);
        int b = ShardedLikeCountStore.slotOf(123456789L);
        assertEquals(a, b);
        assertTrue(a >= 0 && a < ShardedLikeCountStore.SLOTS);
    }

    @Test
    void keyNames_areNamespacedAndNeverCollideWithLegacyKey() {
        String legacy = MediaLikeService.COUNT_KEY_PREFIX + "p1";
        String base = ShardedLikeCountStore.baseKey(MediaLikeService.COUNT_KEY_PREFIX, "p1");
        String shard = ShardedLikeCountStore.shardKey(MediaLikeService.COUNT_KEY_PREFIX, "p1", 7);
        assertFalse(legacy.equals(base));
        assertFalse(legacy.equals(shard));
        assertFalse(base.equals(shard));
        assertEquals(MediaLikeService.COUNT_KEY_PREFIX + "p1:s:7", shard);
    }

    @Test
    void read_aggregatesBaseAndSlots_fromSinglePipeline() {
        stubValueOps();
        when(valueOps.get(anyString())).thenReturn("100");   // base 已存在，跳过迁移
        List<Object> row = new ArrayList<>();
        row.add("100");
        row.add("4");
        for (int s = 1; s < ShardedLikeCountStore.SLOTS; s++) {
            row.add("0");
        }
        // read 内两次 pipeline：① TTL 续期（返回值忽略）② GET 聚合
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of(), row);

        assertEquals(104L, store(true).read(MediaLikeService.COUNT_KEY_PREFIX, "p1"));
    }

    @Test
    void readMany_indexAlignedAcrossTwoPosts() {
        // 两个 base 都已存在，ensureBases 不会走 opsForValue()，故此处不 stub，避免严格模式误报
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(inv -> {
            if (call.getAndIncrement() == 0) {
                // ① ensureBases 的探测 pipeline：两个 base 都已存在，不触发迁移
                return List.of("10", "20");
            }
            // ② GET 聚合 pipeline：连续 2×(1+64) 个结果
            List<Object> res = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                res.add(i == 0 ? "100" : "200");
                res.add(i == 0 ? "5" : "7");
                for (int s = 1; s < ShardedLikeCountStore.SLOTS; s++) {
                    res.add("0");
                }
            }
            return res;
        });

        Map<String, Long> out = store(true).readMany(
                MediaLikeService.COUNT_KEY_PREFIX, List.of("p1", "p2"));

        assertEquals(2, out.size());
        assertEquals(105L, out.get("p1"));
        assertEquals(207L, out.get("p2"));
    }

    @Test
    void ensureBase_migratesLegacySnapshotOnlyWhenBaseAbsent() {
        stubValueOps();
        when(valueOps.get(anyString())).thenReturn(null, "888");  // base=null → legacy=888
        when(valueOps.setIfAbsent(anyString(), eq("888"), any(Duration.class))).thenReturn(true);

        store(true).bump(MediaLikeService.COUNT_KEY_PREFIX, "p1", 9L, 1);

        verify(valueOps).setIfAbsent(
                eq(MediaLikeService.COUNT_KEY_PREFIX + "p1:s:base"), eq("888"), any(Duration.class));
    }

    @Test
    void foldBack_writesTotalToLegacyKey_andRemovesShardedData() {
        stubValueOps();
        when(valueOps.get(anyString())).thenReturn("100");
        List<Object> row = new ArrayList<>();
        row.add("100");
        row.add("6");
        for (int s = 1; s < ShardedLikeCountStore.SLOTS; s++) {
            row.add("0");
        }
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of(), row);

        long total = store(true).foldBack(MediaLikeService.COUNT_KEY_PREFIX, "p1");

        assertEquals(106L, total);
        verify(valueOps).set(eq(MediaLikeService.COUNT_KEY_PREFIX + "p1"), eq("106"), any(Duration.class));
        verify(redis).delete(any(List.class));
    }

    @Test
    void mediaLikeService_usesShardedReadWhenEnabled() {
        MediaLikeService svc = new MediaLikeService(
                redis, mock(BehaviorEventPublisher.class), mock(MediaLikeJdbcRepository.class),
                mock(SnowflakeIdGenerator.class), Runnable::run, mock(UploadRateLimiter.class),
                mock(ObjectProvider.class), mock(NotificationGenerator.class));
        ShardedLikeCountStore sharded = mock(ShardedLikeCountStore.class);
        when(sharded.isEnabled()).thenReturn(true);
        when(sharded.read(eq(MediaLikeService.COUNT_KEY_PREFIX), eq("p1"))).thenReturn(42L);
        ReflectionTestUtils.setField(svc, "shardedCountStore", sharded);

        assertEquals(42L, svc.likeCount("p1"));
        verify(sharded).read(MediaLikeService.COUNT_KEY_PREFIX, "p1");
    }

    @Test
    void mediaLikeService_fallsBackToLegacyPathWhenStoreAbsent() {
        // 单测直接 new 时字段为 null：必须等同「开关关闭」，不能 NPE
        stubValueOps();
        when(valueOps.get(anyString())).thenReturn("7");
        MediaLikeService svc = new MediaLikeService(
                redis, mock(BehaviorEventPublisher.class), mock(MediaLikeJdbcRepository.class),
                mock(SnowflakeIdGenerator.class), Runnable::run, mock(UploadRateLimiter.class),
                mock(ObjectProvider.class), mock(NotificationGenerator.class));
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of());

        assertEquals(7L, svc.likeCount("p1"));
    }
}
