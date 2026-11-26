package com.turbofeed.gateway.service.share;

import com.turbofeed.hotspot.cache.CacheInvalidationPublisher;
import com.turbofeed.hotspot.cache.LocalCacheWithBroadcast;
import com.turbofeed.shared.feature.FeatureFlagKeys;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 热点短链映射的进程内 L1 缓存（方案 §4.6 / P0-3b）。
 *
 * <p><b>为什么不拆 key</b>：短链是点查单 key（{@code WHERE code = ?}），拆槽只会把一次读变成 N 次读。
 * 单个短链被爆量访问时，映射 immutable（生成后不变），天生适合进程内 L1；多实例靠 pub/sub 失效广播
 * 把不一致窗口从整段 TTL 压到一次网络往返。</p>
 *
 * <p><b>缓存对象</b>：只缓存 {@link ShareRecord} 映射，<b>不缓存内容状态</b>。状态校验每次走
 * {@code MediaQueryService.statusOf()}（自身带 60s 旁路缓存），内容下架后不会因 L1 命中旧映射而复活。</p>
 *
 * <p><b>降级</b>：{@code enabled=false} 或 Redis 不可用时，{@link LocalCacheWithBroadcast} 自动退化为
 * 直调 loader（打 Redis/DB）；广播失败退化为 TTL 兜底。缓存永不阻断主链路。</p>
 */
@Component
public class ShareMappingCache {

    private static final Logger log = LoggerFactory.getLogger(ShareMappingCache.class);

    /** 跨实例失效广播频道（独立频道，不与账号状态缓存混用）。 */
    public static final String INVALIDATE_CHANNEL = "turbofeed:cache:share-mapping:invalidate";

    /** 进程级实例标识：同进程内所有缓存实例共用，保证「自己发的广播自己跳过」。 */
    private final String instanceId = UUID.randomUUID().toString();

    private final LocalCacheWithBroadcast<String, ShareRecord> cache;
    private final boolean broadcastEnabled;

    public ShareMappingCache(CacheInvalidationPublisher publisher,
                             @Value("${" + FeatureFlagKeys.SHARE_CACHE_ENABLED + ":true}") boolean enabled,
                             @Value("${" + FeatureFlagKeys.SHARE_CACHE_TTL_SECONDS + ":60}") long ttlSeconds,
                             @Value("${" + FeatureFlagKeys.SHARE_CACHE_MAX_ENTRIES + ":10000}") int maxEntries,
                             @Value("${turbofeed.share.cache-broadcast-enabled:true}") boolean broadcastEnabled,
                             @Value("${turbofeed.share.cache-broadcast-retries:1}") int broadcastRetries) {
        this.broadcastEnabled = broadcastEnabled;
        long ttlMillis = Math.max(0L, ttlSeconds) * 1000L;
        this.cache = new LocalCacheWithBroadcast<>(INVALIDATE_CHANNEL, enabled, ttlMillis, maxEntries,
                broadcastEnabled, broadcastRetries, publisher, instanceId, key -> key);
        log.info("ShareMappingCache 已委托 tf-hotspot（enabled={}, ttl={}ms, maxEntries={}, broadcast={}）",
                enabled, ttlMillis, maxEntries, broadcastEnabled);
    }

    /** 带 L1 缓存的映射加载。loader 只在未命中 / 过期 / 已失效 / 缓存关闭时被调用。 */
    public ShareRecord getOrLoad(String code, Supplier<ShareRecord> loader) {
        return cache.getOrLoad(code, loader);
    }

    /** 运营作废 / 内容下架时失效映射并广播（本实例立即生效，其它实例一次往返收敛）。 */
    public void invalidate(String code) {
        cache.invalidate(code);
    }

    /** 订阅方回调专用：仅本实例失效，绝不再次广播（避免回环风暴）。 */
    public void invalidateLocal(String code) {
        cache.invalidateLocal(code);
    }

    /**
     * 处理收到的失效广播。返回是否真的失效了一个条目（订阅方日志与排障用）。
     * 消息格式 {@code instanceId:code}，由 SDK 解析与去重。
     */
    public boolean onInvalidateMessage(String message) {
        return cache.onInvalidateMessage(message);
    }

    /** 周期清理过期条目，避免过期条目常驻堆（调度频率由方法上的 fixedDelayString 决定）。 */
    @Scheduled(fixedDelayString = "${turbofeed.share.cache-sweep-interval-ms:30000}")
    public void sweepExpired() {
        cache.sweepExpired();
    }

    /** 缓存条目数（巡检 / 排障 / 单测用）。 */
    public int size() {
        return cache.size();
    }

    public boolean isBroadcastEnabled() {
        return broadcastEnabled;
    }
}
