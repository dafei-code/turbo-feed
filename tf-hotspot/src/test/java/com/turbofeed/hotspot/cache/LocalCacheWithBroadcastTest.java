package com.turbofeed.hotspot.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalCacheWithBroadcast 纯单测（不依赖 Redis / Spring）：
 * 用 RecordingPublisher 捕获广播 payload，并模拟跨实例投递，验证 TTL / version stamp / 广播 / 退化 / 清理语义。
 */
class LocalCacheWithBroadcastTest {

    /** 记录广播频道与 payload；可切换为「发送即抛」以验证退化。 */
    static class RecordingPublisher implements CacheInvalidationPublisher {
        final List<String> channels = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        boolean throwOnPublish = false;

        @Override
        public void publish(String channel, String payload) {
            if (throwOnPublish) {
                throw new RuntimeException("redis down");
            }
            channels.add(channel);
            payloads.add(payload);
        }
    }

    private LocalCacheWithBroadcast<Long, String> newCache(String instanceId, RecordingPublisher pub,
                                                           boolean enabled, int maxEntries) {
        return new LocalCacheWithBroadcast<>("ch", enabled, 1000L, maxEntries,
                true, 1, pub, instanceId, Long::parseLong);
    }

    @Test
    void ttlHitDoesNotReload() {
        RecordingPublisher pub = new RecordingPublisher();
        var cache = newCache("A", pub, true, 1000);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        assertEquals("v1", cache.getOrLoad(1L, loader));
        assertEquals("v1", cache.getOrLoad(1L, loader)); // 命中，不重载
        assertEquals(1, loads.get());
    }

    @Test
    void invalidateForcesReloadWithinTtl() {
        RecordingPublisher pub = new RecordingPublisher();
        var cache = newCache("A", pub, true, 1000);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        assertEquals("v1", cache.getOrLoad(1L, loader));
        cache.invalidate(1L); // version stamp → 即使未过期也必须 reload
        assertEquals("v2", cache.getOrLoad(1L, loader));
        assertEquals(2, loads.get());
        assertTrue(pub.payloads.contains("A:1"), "应广播失效消息");
    }

    @Test
    void crossInstanceInvalidationReloadsOtherInstance() {
        RecordingPublisher pubA = new RecordingPublisher();
        var cacheA = newCache("A", pubA, true, 1000);
        RecordingPublisher pubB = new RecordingPublisher();
        var cacheB = newCache("B", pubB, true, 1000);

        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        cacheB.getOrLoad(1L, loader); // B 先加载 → v1
        assertEquals(1, loads.get());

        cacheA.invalidate(1L); // A 失效并广播
        String payload = pubA.payloads.get(pubA.payloads.size() - 1);
        assertTrue(cacheB.onInvalidateMessage(payload), "B 应处理跨实例消息");

        cacheB.getOrLoad(1L, loader); // B 失效后必 reload → v2
        assertEquals(2, loads.get());
    }

    @Test
    void selfMessageIsSkipped() {
        RecordingPublisher pub = new RecordingPublisher();
        var cacheB = newCache("B", pub, true, 1000);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        cacheB.getOrLoad(1L, loader);
        assertFalse(cacheB.onInvalidateMessage("B:1"), "自己发的广播应跳过，不触发失效");
        cacheB.getOrLoad(1L, loader); // 不重载
        assertEquals(1, loads.get());
    }

    @Test
    void sweepEvictsExpired() throws InterruptedException {
        RecordingPublisher pub = new RecordingPublisher();
        // TTL=1ms：加载后稍等即过期
        var cache = new LocalCacheWithBroadcast<Long, String>("ch", true, 1L, 1000,
                false, 0, pub, "A", Long::parseLong);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        cache.getOrLoad(1L, loader);
        cache.getOrLoad(2L, loader);
        Thread.sleep(10);
        cache.sweepExpired();
        assertEquals(0, cache.size());
    }

    /**
     * 超限必须<b>有界淘汰</b>而不是整体清空：旧实现（{@code map.clear()}）会让一次冷 key 扫描
     * 把所有热条目一起冲掉，命中率骤降——这是「热/冷桶迁移未做」的具体缺口。
     */
    @Test
    void maxEntriesIsBoundedWithoutWholeClear() {
        RecordingPublisher pub = new RecordingPublisher();
        var cache = newCache("A", pub, true, 2); // 上限 2
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        cache.getOrLoad(1L, loader);
        cache.getOrLoad(2L, loader);
        cache.getOrLoad(3L, loader); // 超过上限 → 按冷段 LRU 淘汰，绝不整体清空

        assertTrue(cache.size() <= 2, "必须维持硬上限，实际=" + cache.size());
        assertTrue(cache.size() >= 1, "至少保留刚写入的键");

        int before = loads.get();
        cache.getOrLoad(3L, loader);
        assertEquals(before, loads.get(), "刚写入的键必须保留（旧实现会被整体清空而重载）");
    }

    @Test
    void coldEntryPromotedToHotAfterSecondAccess() {
        RecordingPublisher pub = new RecordingPublisher();
        var cache = newCache("A", pub, true, 100);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        cache.getOrLoad(7L, loader);
        assertFalse(cache.isHot(7L), "首次加载应落在冷段");
        cache.getOrLoad(7L, loader); // 二次访问
        assertTrue(cache.isHot(7L), "二次访问应提升为热条目");
    }

    /**
     * 核心回归：一次大量冷 key 写入（典型如榜单/索引扫描）不得把热条目淘汰掉。
     * 旧实现「超限整体 clear」在此用例下必然失败。
     */
    @Test
    void hotEntriesSurviveColdBurst() {
        RecordingPublisher pub = new RecordingPublisher();
        var cache = newCache("A", pub, true, 50);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        cache.getOrLoad(1L, loader);
        cache.getOrLoad(1L, loader); // 提升为热
        assertTrue(cache.isHot(1L));

        for (long k = 100L; k < 300L; k++) { // 200 个冷 key 洪泛，容量只有 50
            cache.getOrLoad(k, loader);
        }

        int before = loads.get();
        cache.getOrLoad(1L, loader);
        assertEquals(before, loads.get(), "冷 key 洪泛后热条目必须仍命中");
        assertTrue(cache.size() <= 50, "冷洪泛后仍须维持硬上限，实际=" + cache.size());
    }

    @Test
    void broadcastFailureDegradesWithoutThrowing() {
        RecordingPublisher failing = new RecordingPublisher();
        failing.throwOnPublish = true;
        var cache = newCache("A", failing, true, 1000);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        cache.getOrLoad(1L, loader);
        cache.invalidate(1L); // 广播失败不应抛异常
        assertEquals("v2", cache.getOrLoad(1L, loader)); // 本地仍失效，触发重载
        assertEquals(2, loads.get());
    }

    @Test
    void disabledCacheAlwaysReloads() {
        RecordingPublisher pub = new RecordingPublisher();
        var cache = newCache("A", pub, false, 1000);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        assertEquals("v1", cache.getOrLoad(1L, loader));
        assertEquals("v2", cache.getOrLoad(1L, loader)); // 关闭 → 每次直读
        assertEquals(2, loads.get());
    }
}
