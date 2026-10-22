package com.turbofeed.hotspot.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 通用「本地缓存（L1）+ Redis pub/sub 失效广播」组件——把账号状态缓存的成熟模式抽成可复用 SDK。
 *
 * <p><b>解决什么</b>：多实例部署下，进程内缓存若不协作，状态变更后其它实例要等整段 TTL 才看见
 * （最长 TTL 的不一致窗口）。本组件在<b>写路径</b>上顺便广播一条失效消息，订阅方收到后只清本地条目，
 * 把窗口压到「一次网络往返」。</p>
 *
 * <h3>一致性模型（version stamp 防脏窗口）</h3>
 * <ol>
 *   <li><b>本实例写入</b>：{@link #invalidate} 先打 version stamp（{@code invalidatedAt[key]=now}）再清本地，
 *       不存在「自己改了自己看不见」。</li>
 *   <li><b>跨实例写入</b>：写方在广播里带上 {@code instanceId}，订阅方收到后打 version stamp 并清本地。
 *       读路径仅在 {@code loadedAt >= invalidatedAt} 时命中——无论本地还是跨实例（含晚到/乱序）失效，
 *       都保证「失效后首次读取必 reload」，把脏窗口从「整个 TTL」压到「一次网络往返内」。</li>
 *   <li><b>广播不可用自动退化</b>：发布失败被 {@link CacheInvalidationPublisher} 实现方吞掉（或本组件重试后仍失败），
 *       回退到「TTL 兜底」——<b>不会让写路径因 Redis 抖动而失败</b>。</li>
 * </ol>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li><b>超限整体清空</b>：条目数超过 {@code maxEntries} 时直接 {@code clear()}（非 LRU）。
 *       保护性熔断：宁可缓存整体失效退回直读，也不让 map 无界增长拖垮堆。</li>
 *   <li><b>周期清理过期条目</b>：{@link #sweepExpired()} 摘掉过期条目并清理过期的 {@code invalidatedAt} 标记，
 *       避免「过期却永不被访问」的条目常驻堆里。调度（@Scheduled）由接入方负责，本组件只暴露方法。</li>
 *   <li><b>可关闭</b>：{@code enabled=false} 退回直读（每次走 loader）；{@code broadcastEnabled=false} 只关广播、保留本地缓存。</li>
 *   <li><b>不耦合框架</b>：本类零 Spring / 零 Redis 依赖，仅依赖 {@link CacheInvalidationPublisher} 契约，
 *       可在任意模块复用（gateway / feed-engine / counter）。</li>
 * </ul>
 *
 * @param <K> 缓存键类型（{@code toString()} 必须稳定可逆；{@code keyParser} 负责反向解析）
 * @param <V> 缓存值类型
 */
public class LocalCacheWithBroadcast<K, V> {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheWithBroadcast.class);

    private final String channel;
    private final boolean enabled;
    private final long ttlMillis;
    private final int maxEntries;
    private final boolean broadcastEnabled;
    private final int broadcastRetries;
    private final CacheInvalidationPublisher publisher;
    /** 进程级实例标识：同进程多个缓存实例应共用，保证「自己发的广播自己跳过」（避免回环）。 */
    private final String instanceId;
    /** 把消息体中的 key 片段反向解析为 K（如 {@code Long::parseLong}）。 */
    private final Function<String, K> keyParser;

    private final ConcurrentHashMap<K, Holder<V>> map = new ConcurrentHashMap<>();
    /** 每 key「最近一次失效时刻」的 version stamp（#跨实例失效脏窗口修复）。 */
    private final ConcurrentHashMap<K, Long> invalidatedAt = new ConcurrentHashMap<>();

    public LocalCacheWithBroadcast(String channel,
                                   boolean enabled,
                                   long ttlMillis,
                                   int maxEntries,
                                   boolean broadcastEnabled,
                                   int broadcastRetries,
                                   CacheInvalidationPublisher publisher,
                                   String instanceId,
                                   Function<String, K> keyParser) {
        this.channel = channel;
        this.enabled = enabled;
        this.ttlMillis = Math.max(0L, ttlMillis);
        this.maxEntries = Math.max(1, maxEntries);
        this.broadcastEnabled = broadcastEnabled;
        this.broadcastRetries = Math.max(0, broadcastRetries);
        this.publisher = publisher;
        this.instanceId = instanceId;
        this.keyParser = keyParser;
    }

    /** 取值（带缓存）。loader 仅在未命中 / 已过期 / 缓存关闭 / 已被失效时被调用。 */
    public V getOrLoad(K key, Supplier<V> loader) {
        if (!enabled) {
            return loader.get();
        }
        long now = System.currentTimeMillis();
        Holder<V> hit = map.get(key);
        long invalidatedAt = this.invalidatedAt.getOrDefault(key, 0L);
        if (hit != null && hit.expireAt() > now && hit.loadedAt() >= invalidatedAt) {
            // 未过期 且 加载时刻不早于本实例最近一次失效 → 命中有效（含跨实例失效的 version stamp 判定）
            return hit.value();
        }
        V loaded = loader.get();
        put(key, new Holder<>(loaded, now + ttlMillis, now));
        return loaded;
    }

    /**
     * 失效某 key，并<b>广播</b>给其它实例（先本地失效，再发消息）。
     *
     * <p>广播失败不影响主流程：最多重试 {@code broadcastRetries} 次后仍失败则退化 TTL 兜底。
     * 缓存是加速手段，绝不能反向拖垮写路径。</p>
     */
    public void invalidate(K key) {
        invalidatedAt.put(key, System.currentTimeMillis());
        invalidateLocal(key);
        if (!broadcastEnabled) {
            return;
        }
        String payload = instanceId + ":" + key.toString();
        int attempts = 1 + broadcastRetries;
        for (int i = 0; i < attempts; i++) {
            try {
                publisher.publish(channel, payload);
                return;
            } catch (Exception e) {
                // Redis 抖动 → 退化到「TTL 兜底」。失败频率可能很高（每次写都会走），用 debug 避免刷屏。
                log.debug("缓存失效广播发送失败(第{}/{}次，退化 TTL 兜底): channel={}, key={}, {}",
                        i + 1, attempts, channel, key, e.toString());
            }
        }
    }

    /** 仅本实例失效（订阅方回调走这里——<b>绝不再次广播</b>，否则形成回环风暴）。 */
    public void invalidateLocal(K key) {
        map.remove(key);
    }

    /**
     * 处理收到的广播消息（格式 {@code instanceId:key}）。
     *
     * <p>收到即打 version stamp（用本实例接收时刻），使晚到/乱序广播也能正确触发 reload——
     * 因为接收时刻一定晚于被它失效的本地缓存的 loadedAt。</p>
     *
     * @return 是否真的失效了一个条目（订阅方日志与排障用）
     */
    public boolean onInvalidateMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        int sep = message.indexOf(':');
        if (sep <= 0) {
            return false;
        }
        String from = message.substring(0, sep);
        if (instanceId.equals(from)) {
            return false; // 自己发的，跳过（同进程多个缓存实例共用 instanceId，避免回环）
        }
        try {
            K key = keyParser.apply(message.substring(sep + 1));
            invalidatedAt.put(key, System.currentTimeMillis());
            invalidateLocal(key);
            log.debug("收到跨实例缓存失效广播: channel={}, key={}, from={}", channel, key, from);
            return true;
        } catch (RuntimeException e) {
            log.warn("缓存失效广播格式非法: channel={}, message={}", channel, message);
            return false;
        }
    }

    /**
     * 周期清理过期条目：把 {@code expireAt} 已过的条目摘掉，避免「过期却永不被访问」常驻堆里；
     * 同时清理过期的 {@code invalidatedAt} 标记，防止该 map 随失效次数无限增长。
     *
     * <p>调度（@Scheduled）由接入方负责，本组件只暴露方法。</p>
     */
    public void sweepExpired() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        evictExpired(now);
        Iterator<Map.Entry<K, Long>> it = invalidatedAt.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now - ttlMillis * 2) {
                it.remove();
            }
        }
    }

    /** 缓存条目数（巡检 / 排障用，不含 version stamp 标记表）。 */
    public int size() {
        return map.size();
    }

    private void put(K key, Holder<V> holder) {
        if (map.size() >= maxEntries) {
            // 保护性熔断：整体清空（非 LRU）——缓存是加速手段，绝不能成为内存风险源。
            map.clear();
            log.warn("本地缓存达到上限 {}，已整体清空（退回直读）: channel={}", maxEntries, channel);
        }
        map.put(key, holder);
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<K, Holder<V>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireAt() <= now) {
                it.remove();
            }
        }
    }

    /** 带过期时点与加载时点的缓存条目（不可变）。loadedAt 供跨实例失效 version stamp 判定。 */
    private record Holder<V>(V value, long expireAt, long loadedAt) {
    }
}
