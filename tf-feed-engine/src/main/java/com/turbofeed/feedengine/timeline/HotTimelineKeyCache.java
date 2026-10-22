package com.turbofeed.feedengine.timeline;

import com.turbofeed.hotspot.cache.CacheInvalidationPublisher;
import com.turbofeed.hotspot.cache.LocalCacheWithBroadcast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 热点榜 L1 本地缓存（P2-6 第二消费者：复用 tf-hotspot {@link LocalCacheWithBroadcast}）。
 *
 * <p><b>为何复用 SDK</b>：{@link PostStatService#hotTimelineKeys} 在<b>每次推荐流组装</b>都读
 * 热点 ZSET（{@code ZREVRANGE}，且为平滑跨零点读两天 = 两次 Redis 往返）。把它缓存成进程内 L1，
 * 把「每请求一次 Redis」降到「每 TTL 一次」，多实例各自缓存、零跨实例协调开销。</p>
 *
 * <p><b>为何关闭广播、只用 TTL 失效（与网关 AccountStateCache 不同）</b>：热点榜的<b>写源是行为事件</b>——
 * 每次点赞/评论/分享/完播都 {@code ZINCRBY}。若照搬网关「每次变更广播」，pub/sub 流量与行为事件同频，
 * 对一个「热门」召回通道毫无必要。热点榜本就是近似信号，5s 延迟（TTL）完全可接受，
 * 故 {@code broadcast-enabled=false}：失效只靠 TTL 兜底，不引入广播基础设施，也不淹没 Redis。</p>
 *
 * <p><b>缓存形状</b>：单键（全局热点榜，所有用户共享同一份）存「Top-CAP 热度内容身份」，
 * 读取时按请求的 {@code n} 切片返回前 n 条。CAP 取较大值（200）以覆盖任意单页 hotSlots。</p>
 */
@Component
public class HotTimelineKeyCache {

    private static final Logger log = LoggerFactory.getLogger(HotTimelineKeyCache.class);

    /** 单键：全局热点榜（所有用户共享）。 */
    static final int KEY = 0;
    /** 缓存的热点榜容量上限（远大于单页 hotSlots，保证任意 n 都能切片）。 */
    static final int CAP = 200;

    private final LocalCacheWithBroadcast<Integer, List<String>> cache;

    public HotTimelineKeyCache(@Value("${turbofeed.feed.hotkey-cache.ttl-seconds:5}") long ttlSeconds,
                               @Value("${turbofeed.feed.hotkey-cache.broadcast-enabled:false}") boolean broadcastEnabled) {
        long ttlMillis = Math.max(0L, ttlSeconds) * 1000L;
        // 广播关闭：用 no-op 发布者（SDK 要求非 null，但 broadcastEnabled=false 时不会调用）。
        CacheInvalidationPublisher noop = (channel, payload) -> { };
        this.cache = new LocalCacheWithBroadcast<>("turbofeed:cache:hot-timeline-keys",
                true, ttlMillis, 1000, broadcastEnabled, 0, noop,
                UUID.randomUUID().toString(), Integer::parseInt);
        log.info("HotTimelineKeyCache 已启用（复用 tf-hotspot LocalCacheWithBroadcast，ttl={}s, broadcast={}）",
                ttlSeconds, broadcastEnabled);
    }

    /**
     * 取热度 Top-{@code n} 的内容身份（带 L1 缓存）。
     *
     * @param n      返回条数上限（≤0 视为 0）
     * @param loader 缓存未命中时加载全局热点榜 Top-CAP（由 {@link PostStatService#rawHotTimelineKeys} 提供）
     * @return timelineKey 列表（按热度降序、最多 n 条）；n≤0 返回空列表
     */
    public List<String> getHotKeys(int n, Supplier<List<String>> loader) {
        if (n <= 0) {
            return List.of();
        }
        List<String> all = cache.getOrLoad(KEY, loader);
        if (all.size() <= n) {
            return all;
        }
        return new ArrayList<>(all.subList(0, n));
    }
}
