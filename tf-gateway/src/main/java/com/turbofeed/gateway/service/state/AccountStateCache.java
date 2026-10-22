package com.turbofeed.gateway.service.state;

import com.turbofeed.gateway.service.review.credit.CreditLevel;
import com.turbofeed.hotspot.cache.CacheInvalidationPublisher;
import com.turbofeed.hotspot.cache.LocalCacheWithBroadcast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 账号状态本地缓存（进程内、短 TTL）：信用等级 + 可写闸门。
 *
 * <p><b>实现已委托给通用 SDK {@link LocalCacheWithBroadcast}（tf-hotspot）</b>：
 * 「本地缓存 + Redis pub/sub 失效广播 + version stamp 防脏窗口 + 广播失败自动退化」
 * 这套模式从本类抽出后，可被时间线热 Key 等其它场景复用。本类仅做<b>两路缓存的装配</b>
 * （level / writeable 各自的 loader 不同）与<b>调度</b>（{@code @Scheduled} sweep）。</p>
 *
 * <p><b>为什么分两路</b>：{@code level} 与 {@code canWrite} 的 loader 不同（信用走 INSERT+SELECT、闸门走 SELECT），
 * 故用两个 {@link LocalCacheWithBroadcast} 实例，共享同一 {@link #INVALIDATE_CHANNEL} 与进程级 {@code instanceId}。
 * 一次 {@link #invalidate(long)} 会对两路各发一次广播（payload 相同、订阅方幂等），流量极低
 * （仅封禁/扣分等写路径触发）。</p>
 *
 * <p>详细的一致性模型、边界与退化语义见 {@link LocalCacheWithBroadcast} 类文档。</p>
 */
@Component
public class AccountStateCache {

    private static final Logger log = LoggerFactory.getLogger(AccountStateCache.class);

    /** 跨实例失效广播频道（保持原值不变，RedisCacheInvalidationConfig 仍订阅此常量）。 */
    public static final String INVALIDATE_CHANNEL = "turbofeed:cache:account-state:invalidate";

    /** 进程级实例标识：level/write 两个 SDK 实例共用，保证「自己发的广播自己跳过」。 */
    private final String instanceId = UUID.randomUUID().toString();

    private final LocalCacheWithBroadcast<Long, CreditLevel> levelCache;
    private final LocalCacheWithBroadcast<Long, Boolean> writeCache;

    public AccountStateCache(@Value("${turbofeed.state-cache.enabled:true}") boolean enabled,
                             @Value("${turbofeed.state-cache.ttl-seconds:15}") long ttlSeconds,
                             @Value("${turbofeed.state-cache.max-entries:100000}") int maxEntries,
                             @Value("${turbofeed.state-cache.broadcast-enabled:true}") boolean broadcastEnabled,
                             @Value("${turbofeed.state-cache.sweep-interval-ms:30000}") long sweepIntervalMillis,
                             @Value("${turbofeed.state-cache.broadcast-retries:1}") int broadcastRetries,
                             CacheInvalidationPublisher publisher) {
        long ttlMillis = Math.max(0L, ttlSeconds) * 1000L;
        this.levelCache = new LocalCacheWithBroadcast<>(INVALIDATE_CHANNEL, enabled, ttlMillis, maxEntries,
                broadcastEnabled, broadcastRetries, publisher, instanceId, Long::parseLong);
        this.writeCache = new LocalCacheWithBroadcast<>(INVALIDATE_CHANNEL, enabled, ttlMillis, maxEntries,
                broadcastEnabled, broadcastRetries, publisher, instanceId, Long::parseLong);
        log.info("AccountStateCache 已委托 tf-hotspot LocalCacheWithBroadcast（level/write 两路，broadcast={}）",
                broadcastEnabled);
    }

    /** 信用等级（带缓存）。loader 只在未命中 / 已过期 / 缓存关闭时被调用。 */
    public CreditLevel level(long userId, Supplier<CreditLevel> loader) {
        return levelCache.getOrLoad(userId, loader);
    }

    /** 可写闸门（带缓存）。语义与 {@code PenaltyService#canWrite} 完全一致。 */
    public boolean canWrite(long userId, Supplier<Boolean> loader) {
        return Boolean.TRUE.equals(writeCache.getOrLoad(userId, loader));
    }

    /**
     * 失效某账号的全部缓存项，并广播给其它实例（level + write 两路各发一次，订阅方幂等）。
     * 写路径（处罚/信用变更）调此。
     */
    public void invalidate(long userId) {
        levelCache.invalidate(userId);
        writeCache.invalidate(userId);
    }

    /** 仅本实例失效（订阅方回调走这里——绝不再次广播）。 */
    public void invalidateLocal(long userId) {
        levelCache.invalidateLocal(userId);
        writeCache.invalidateLocal(userId);
    }

    /**
     * 处理收到的广播消息（格式 {@code instanceId:userId}）。level + write 两路均处理（幂等）。
     *
     * @return 是否真的失效了一个条目
     */
    public boolean onInvalidateMessage(String message) {
        boolean a = levelCache.onInvalidateMessage(message);
        boolean b = writeCache.onInvalidateMessage(message);
        return a || b;
    }

    /** 缓存条目数（巡检 / 排障用）。 */
    public int size() {
        return levelCache.size() + writeCache.size();
    }

    /**
     * 周期清理过期条目（#0039 的「无界」隐患修复）：与 TTL（默认 15s）同量级，
     * 保证过期条目在最多约一个 TTL + 一个扫描周期后被回收，内存占用有平滑上界。
     */
    @Scheduled(initialDelayString = "${turbofeed.state-cache.sweep-initial-delay-ms:60000}",
               fixedDelayString = "${turbofeed.state-cache.sweep-interval-ms:30000}")
    public void sweepExpired() {
        levelCache.sweepExpired();
        writeCache.sweepExpired();
    }
}
