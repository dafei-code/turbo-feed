package com.turbofeed.feedengine.timeline;

import com.turbofeed.hotspot.cache.CacheInvalidationPublisher;
import com.turbofeed.hotspot.cache.LocalCacheWithBroadcast;
import com.turbofeed.shared.model.FeedItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 关注流作者近期内容的 L1 本地缓存（P1，复用 tf-hotspot {@link LocalCacheWithBroadcast}）。
 *
 * <p><b>解决什么</b>：分片后虽然把单个大 V 的读压力摊到 N 个 key，但每个 key 仍是「粉丝每次拉流都读」。
 * 作者索引的写源是<b>过审事件</b>（低频），不是行为事件——因此可以用短 TTL 做 L1，
 * 把「每请求一次 Redis」降到「每 TTL 一次」。5s 延迟对关注流完全可接受。</p>
 *
 * <p><b>为何 TTL-only（broadcast-enabled=false）</b>：广播需要 Redis pub/sub 基础设施与跨实例协调，
 * 而关注流是近似信号，短 TTL 已足够。若后续要求「下架即刻从所有实例缓存消失」，
 * 可打开广播并让写路径调 {@link #invalidate}，SDK 的 version stamp 已保证乱序消息也能触发 reload。</p>
 *
 * <p><b>缓存 key</b>：{@code authorId|viewerShard} —— 分片读的是哪个 Redis key，缓存就按哪份缓存，
 * 保证同一 viewer 始终看到同一分片（否则 L1 会把不同分片的内容串味）。</p>
 */
@Component
public class AuthorRecentCache {

    private static final Logger log = LoggerFactory.getLogger(AuthorRecentCache.class);

    /** 缓存容量上限（按作者×分片维度，远大于单实例活跃大 V 数）。 */
    static final int CAP = 2000;

    private final LocalCacheWithBroadcast<String, List<FeedItemView>> cache;
    private final boolean enabled;

    public AuthorRecentCache(
            @Value("${turbofeed.feed.follow.author-cache-enabled:true}") boolean enabled,
            @Value("${turbofeed.feed.follow.author-cache-ttl-seconds:5}") long ttlSeconds) {
        this.enabled = enabled;
        long ttlMillis = Math.max(0L, ttlSeconds) * 1000L;
        CacheInvalidationPublisher noop = (channel, payload) -> { };
        this.cache = new LocalCacheWithBroadcast<>("turbofeed:cache:author-recent",
                enabled, ttlMillis, CAP, false, 0, noop,
                UUID.randomUUID().toString(), k -> k);
        if (enabled) {
            log.info("AuthorRecentCache 已启用（ttl={}s, TTL-only 失效）", ttlSeconds);
        }
    }

    /** 缓存 key：作者 + 其被读的分片号（viewer 已 hash 到该分片）。 */
    static String cacheKey(String authorId, int shard) {
        return authorId + "|" + shard;
    }

    /** 带 L1 缓存地取作者近期内容；loader 只在未命中/过期时执行。 */
    List<FeedItemView> getOrLoad(String authorId, int shard, Supplier<List<FeedItemView>> loader) {
        if (!enabled) {
            return loader.get();
        }
        return cache.getOrLoad(cacheKey(authorId, shard), loader);
    }

    /** 失效某作者在指定分片上的缓存（下架 / 申诉翻案时由写路径调用）。 */
    public void invalidate(String authorId, int shard) {
        if (enabled) {
            cache.invalidateLocal(cacheKey(authorId, shard));
        }
    }
}
