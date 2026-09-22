package com.turbofeed.gateway.service.state;

import com.turbofeed.gateway.service.review.credit.CreditLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 账号状态本地缓存（进程内、短 TTL）：信用等级 + 可写闸门。
 *
 * <p><b>为什么需要</b>：这两项都挂在<b>写热路径</b>上——每次上传都要
 * {@code AccountCreditService#ensure}（一次 {@code INSERT ... ON DUPLICATE KEY} + 一次 SELECT）
 * 和 {@code PenaltyService#canWrite}（一次 SELECT）。它们读的是「账号级、低频变更」的数据，
 * 却按「每次请求一次 DB」的频次在读。单实例 2000 QPS 上传就意味着 4000 次/秒的纯重复读，
 * 白白吃掉连接池与分片库的行锁/CPU。本地缓存把这两次读降到近乎零。</p>
 *
 * <p><b>为什么是本地缓存而不是 Redis</b>：Redis 能解决多实例一致性，但把一次「本可不发生的读」
 * 换成一次「必然发生的网络往返」，对<em>降低 DB 压力</em>这个目标反而更贵。
 * 因此采用「本地缓存抗读热点 + Redis pub/sub 广播失效保证一致性」——
 * 前者消除 99% 的读，后者把这 99% 带来的不一致窗口从「整个 TTL」压到「一次网络往返」。</p>
 *
 * <p><b>为什么不引 Caffeine</b>：只需要「TTL + 上限」，{@code ConcurrentHashMap} 足够；
 * 引入第三方缓存会带进驱逐线程、权重、统计等一整套配置面，而这里恰恰要的是<b>行为可预测</b>。</p>
 *
 * <h3>一致性模型（补齐广播后的口径）</h3>
 * <ol>
 *   <li><b>本实例写入</b>：处罚/信用的写路径调 {@link #invalidate}，本地立即失效
 *       ——不存在「自己改了自己看不见」。</li>
 *   <li><b>跨实例写入</b>：写方在 {@link #invalidate} 里顺带发一条 Redis pub/sub 广播，
 *       其余实例收到后调 {@link #invalidateLocal}。窗口从「最长 TTL（15s）」压到「一次 Redis 往返」。</li>
 *   <li><b>广播不可用时自动退化</b>：Redis 抖动/未部署时 {@code convertAndSend} 失败被吞掉，
 *       回退到「TTL 兜底」——<b>不会因为 Redis 挂了就让写路径失败</b>。</li>
 * </ol>
 *
 * <h3>其它边界</h3>
 * <ul>
 *   <li><b>超限整体清空</b>：条目数超过 {@code max-entries} 时直接 {@code clear()}（不是 LRU）。
 *       这是保护性熔断：宁可缓存整体失效退回全量读 DB，也不让 map 无界增长拖垮堆。
 *       正常情况下由周期清理（{@link #sweepExpired}）驱逐过期条目，极少触发整体清空。</li>
 *   <li><b>周期清理过期条目</b>：{@code sweep-interval-ms}（默认 30s）周期扫描，
 *       把 {@code expireAt} 已过且不再被访问的条目摘掉——避免「过期但永不被访问」的条目
 *       一直驻留堆里直至触发整体清空（那是 #0039 原先的「无界」隐患）。</li>
 *   <li><b>可一键关闭</b>：{@code turbofeed.state-cache.enabled=false} 退回直读；
 *       {@code broadcast-enabled=false} 只关广播、保留本地缓存。</li>
 * </ul>
 */
@Component
public class AccountStateCache {

    private static final Logger log = LoggerFactory.getLogger(AccountStateCache.class);

    /** 跨实例失效广播频道。发布方与订阅方共用此常量，改一处即可。 */
    public static final String INVALIDATE_CHANNEL = "turbofeed:cache:account-state:invalidate";

    private final boolean enabled;
    private final long ttlMillis;
    private final int maxEntries;
    private final boolean broadcastEnabled;
    private final long sweepIntervalMillis;
    private final int broadcastRetries;
    private final StringRedisTemplate redisTemplate;

    /**
     * 本实例标识：广播消息里带上它，收到自己发的消息直接丢弃
     * （不丢也幂等，但省掉一次无意义的 map 操作，且让日志更干净）。
     */
    private final String instanceId = UUID.randomUUID().toString();

    private final ConcurrentHashMap<Long, Holder<CreditLevel>> levelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Holder<Boolean>> writeCache = new ConcurrentHashMap<>();

    /**
     * 每用户「最近一次失效时刻」的 version stamp（#007 跨实例失效脏窗口修复）。
     *
     * <p>读路径仅在 {@code holder.loadedAt >= invalidatedAt[user]} 时命中缓存——
     * 因此无论本地还是跨实例（晚到/乱序）的失效，都能保证「失效后首次读取必 reload」，
     * 把脏窗口从「整个 TTL」压到「一次 Redis 往返内」（乱序广播会被 version stamp 正确识别）。</p>
     */
    private final ConcurrentHashMap<Long, Long> invalidatedAt = new ConcurrentHashMap<>();

    public AccountStateCache(@Value("${turbofeed.state-cache.enabled:true}") boolean enabled,
                             @Value("${turbofeed.state-cache.ttl-seconds:15}") long ttlSeconds,
                             @Value("${turbofeed.state-cache.max-entries:100000}") int maxEntries,
                             @Value("${turbofeed.state-cache.broadcast-enabled:true}") boolean broadcastEnabled,
                             @Value("${turbofeed.state-cache.sweep-interval-ms:30000}") long sweepIntervalMillis,
                             @Value("${turbofeed.state-cache.broadcast-retries:1}") int broadcastRetries,
                             StringRedisTemplate redisTemplate) {
        this.enabled = enabled;
        this.ttlMillis = Math.max(0L, ttlSeconds) * 1000L;
        this.maxEntries = Math.max(1, maxEntries);
        this.broadcastEnabled = broadcastEnabled;
        this.sweepIntervalMillis = Math.max(1_000L, sweepIntervalMillis);
        this.broadcastRetries = Math.max(0, broadcastRetries);
        this.redisTemplate = redisTemplate;
    }

    /** 信用等级（带缓存）。loader 只在未命中 / 已过期 / 缓存关闭时被调用。 */
    public CreditLevel level(long userId, Supplier<CreditLevel> loader) {
        return enabled ? getOrLoad(levelCache, userId, loader) : loader.get();
    }

    /** 可写闸门（带缓存）。语义与 {@code PenaltyService#canWrite} 完全一致。 */
    public boolean canWrite(long userId, Supplier<Boolean> loader) {
        return enabled ? Boolean.TRUE.equals(getOrLoad(writeCache, userId, loader)) : Boolean.TRUE.equals(loader.get());
    }

    /**
     * 失效某账号的全部缓存项，并<b>广播</b>给其它实例（先本地失效，再发消息）。
     *
     * <p><b>#007 跨实例失效脏窗口修复</b>：本地与广播发送前先打 version stamp
     * （{@code invalidatedAt[user]=now}）。读路径仅在 {@code loadedAt >= invalidatedAt} 时命中，
     * 因此无论本地还是跨实例（含晚到/乱序）的失效，都保证「失效后首次读取必 reload」，
     * 把脏窗口压到一次 Redis 往返内。</p>
     *
     * <p>广播失败不影响主流程：最多重试 {@code broadcast-retries} 次后仍失败则吞异常、由 TTL 兜底。
     * 缓存是加速手段，绝不能反向拖垮写路径。</p>
     */
    public void invalidate(long userId) {
        invalidatedAt.put(userId, System.currentTimeMillis());
        invalidateLocal(userId);
        if (!broadcastEnabled) {
            return;
        }
        String payload = instanceId + ":" + userId;
        int attempts = 1 + broadcastRetries;
        for (int i = 0; i < attempts; i++) {
            try {
                redisTemplate.convertAndSend(INVALIDATE_CHANNEL, payload);
                return;
            } catch (Exception e) {
                // Redis 抖动 → 退化到「TTL 兜底」。失败频率可能很高（每次处罚写都会走），用 debug 避免刷屏，
                // 由巡检/监控发现（一致性有 TTL + version stamp 双兜底，不会无限发散）。
                log.debug("缓存失效广播发送失败(第{}/{}次，退化 TTL 兜底): userId={}, {}",
                        i + 1, attempts, userId, e.toString());
            }
        }
    }

    /** 仅本实例失效（订阅方回调走这里——<b>绝不再次广播</b>，否则形成回环风暴）。 */
    public void invalidateLocal(long userId) {
        levelCache.remove(userId);
        writeCache.remove(userId);
    }

    /**
     * 处理收到的广播消息（格式 {@code instanceId:userId}）。
     *
     * <p>收到即打 version stamp（用本实例接收时刻），使晚到/乱序广播也能正确触发 reload——
     * 因为接收时刻一定晚于被它失效的本地缓存的 loadedAt。</p>
     *
     * @param message 频道消息体
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
            return false; // 自己发的，跳过
        }
        try {
            long userId = Long.parseLong(message.substring(sep + 1));
            invalidatedAt.put(userId, System.currentTimeMillis());
            invalidateLocal(userId);
            log.debug("收到跨实例缓存失效广播: userId={}, from={}", userId, from);
            return true;
        } catch (NumberFormatException e) {
            log.warn("缓存失效广播格式非法: {}", message);
            return false;
        }
    }

    /** 缓存条目数（巡检 / 排障用，不含 version stamp 标记表）。 */
    public int size() {
        return levelCache.size() + writeCache.size();
    }

    private <T> T getOrLoad(ConcurrentHashMap<Long, Holder<T>> map, long userId, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Holder<T> hit = map.get(userId);
        long invalidatedAt = this.invalidatedAt.getOrDefault(userId, 0L);
        if (hit != null && hit.expireAt > now && hit.loadedAt >= invalidatedAt) {
            // 未过期 且 加载时刻不早于本实例最近一次失效 → 命中有效（含跨实例失效的 version stamp 判定，见 #7）
            return hit.value;
        }
        T loaded = loader.get();
        map.put(userId, new Holder<>(loaded, now + ttlMillis, now));
        return loaded;
    }

    private <T> void put(ConcurrentHashMap<Long, Holder<T>> map, long userId, Holder<T> holder) {
        if (map.size() >= maxEntries) {
            // 保护性熔断：整体清空（不是 LRU）——缓存是加速手段，绝不能成为内存风险源。
            // 正常情况下由 sweepExpired 周期驱逐过期条目，极少触发此处。
            map.clear();
            log.warn("账号状态缓存条目数达到上限 {}，已整体清空（退回全量读库）", maxEntries);
        }
        map.put(userId, holder);
    }

    /**
     * 周期清理过期条目（#0039 的「无界」隐患修复）：把 {@code expireAt} 已过的条目摘掉，
     * 避免「过期却永不被访问」的条目一直驻留堆里。同时清理过期的 {@code invalidatedAt} 标记，
     * 防止该 map 随失效次数无限增长。
     *
     * <p>固定 30s（可配 {@code sweep-interval-ms}）：与 TTL（默认 15s）同量级，
     * 保证过期条目在最多约一个 TTL + 一个扫描周期后被回收，内存占用有平滑上界。</p>
     */
    @Scheduled(initialDelayString = "${turbofeed.state-cache.sweep-initial-delay-ms:60000}",
               fixedDelayString = "${turbofeed.state-cache.sweep-interval-ms:30000}")
    public void sweepExpired() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        int removed = 0;
        removed += evictExpired(levelCache, now);
        removed += evictExpired(writeCache, now);
        // 清理过期的失效标记（早于 2*TTL 的标记已无意义，留着只会让后续读多一次 reload）
        Iterator<java.util.Map.Entry<Long, Long>> it = invalidatedAt.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now - ttlMillis * 2) {
                it.remove();
            }
        }
        if (removed > 0) {
            log.debug("账号状态缓存周期清理过期条目: removed={}, size={}", removed, size());
        }
    }

    private static <T> int evictExpired(ConcurrentHashMap<Long, Holder<T>> map, long now) {
        int removed = 0;
        Iterator<java.util.Map.Entry<Long, Holder<T>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Holder<T> h = it.next().getValue();
            if (h.expireAt <= now) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 带过期时点与加载时点的缓存条目（不可变）。loadedAt 供跨实例失效 version stamp 判定（#7）。 */
    private record Holder<T>(T value, long expireAt, long loadedAt) {
    }
}
