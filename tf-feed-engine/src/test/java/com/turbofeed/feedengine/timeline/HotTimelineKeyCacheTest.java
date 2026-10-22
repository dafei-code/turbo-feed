package com.turbofeed.feedengine.timeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HotTimelineKeyCache 纯单测（不依赖 Redis / Spring）：验证 L1 缓存 + 按 n 切片语义。
 * 底层 LocalCacheWithBroadcast 的 TTL/清理/广播逻辑由 LocalCacheWithBroadcastTest 覆盖。
 */
class HotTimelineKeyCacheTest {

    private Supplier<List<String>> loader(AtomicInteger loads) {
        return () -> {
            loads.incrementAndGet();
            List<String> l = new ArrayList<>();
            l.add("a");
            l.add("b");
            l.add("c");
            return l;
        };
    }

    @Test
    void cachesAndSlicesToN() {
        var cache = new HotTimelineKeyCache(5L, false);
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<String>> loader = loader(loads);

        assertEquals(List.of("a", "b"), cache.getHotKeys(2, loader));
        assertEquals(1, loads.get(), "首次应加载");

        assertEquals(List.of("a", "b"), cache.getHotKeys(2, loader));
        assertEquals(1, loads.get(), "命中缓存不应重载");
    }

    @Test
    void differentNReusesSameCachedList() {
        var cache = new HotTimelineKeyCache(5L, false);
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<String>> loader = loader(loads);

        assertEquals(List.of("a"), cache.getHotKeys(1, loader));
        assertEquals(1, loads.get());
        assertEquals(List.of("a", "b", "c"), cache.getHotKeys(3, loader));
        assertEquals(1, loads.get(), "不同 n 复用同一份缓存，不重载");
    }

    @Test
    void nLe0ReturnsEmpty() {
        var cache = new HotTimelineKeyCache(5L, false);
        AtomicInteger loads = new AtomicInteger();
        assertTrue(cache.getHotKeys(0, loader(loads)).isEmpty());
        assertTrue(cache.getHotKeys(-1, loader(loads)).isEmpty());
        assertEquals(0, loads.get(), "n<=0 不应触发加载");
    }

    @Test
    void ttlExpiryReloads() throws InterruptedException {
        // ttlSeconds=1 → 1000ms；睡眠超过 TTL 即过期应重载
        var cache = new HotTimelineKeyCache(1L, false);
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<String>> loader = loader(loads);

        cache.getHotKeys(2, loader);
        assertEquals(1, loads.get());
        Thread.sleep(1100);
        cache.getHotKeys(2, loader);
        assertEquals(2, loads.get(), "TTL 过期后应重新加载");
    }
}
