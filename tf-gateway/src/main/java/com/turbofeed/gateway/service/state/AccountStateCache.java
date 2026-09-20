package com.turbofeed.gateway.service.state;

import com.turbofeed.gateway.service.review.credit.CreditLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * 换成一次「必然发生的网络往返」，对<em>降低 DB 压力</em>这个目标反而更贵。正确组合是
 * 「本地缓存抗读热点 + Redis 广播失效保证一致性」——本类先解决前者（收益确定、零依赖），
 * 后者见下方「已知边界」。</p>
 *
 * <p><b>为什么不引 Caffeine</b>：只需要「TTL + 上限」，{@code ConcurrentHashMap} 足够；
 * 引入第三方缓存会带进驱逐线程、权重、统计等一整套配置面，而这里恰恰要的是<b>行为可预测</b>。</p>
 *
 * <h3>已知边界（必须知道，否则会误用）</h3>
 * <ol>
 *   <li><b>跨实例延迟</b>：A 实例封禁了某账号，B 实例的缓存最长 {@code ttl-seconds}（默认 15s）
 *       后才失效，期间该账号在 B 上仍可写。封禁是人工低频操作，15s 漏放窗口可接受；
 *       若要「立即生效」，下一步加 Redis pub/sub 广播 {@link #invalidate} 即可（本类已留出该入口）。</li>
 *   <li><b>本地写入立即失效</b>：本实例的处罚/信用写入路径（{@code recordViolation} /
 *       {@code liftPenalty} / 扣分恢复）都会主动 {@link #invalidate}，不存在「自己改了自己看不见」。</li>
 *   <li><b>超限整体清空</b>：条目数超过 {@code max-entries} 时直接 {@code clear()}（不是 LRU）。
 *       这是保护性熔断：宁可缓存整体失效退回全量读 DB，也不让 map 无界增长拖垮堆。</li>
 *   <li><b>可一键关闭</b>：{@code turbofeed.state-cache.enabled=false} 即退回直读，
 *       用于「怀疑缓存导致状态不更新」时的快速回滚（与内容安全总开关同手法）。</li>
 * </ol>
 */
@Component
public class AccountStateCache {

    private static final Logger log = LoggerFactory.getLogger(AccountStateCache.class);

    private final boolean enabled;
    private final long ttlMillis;
    private final int maxEntries;

    private final ConcurrentHashMap<Long, Holder<CreditLevel>> levelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Holder<Boolean>> writeCache = new ConcurrentHashMap<>();

    public AccountStateCache(@Value("${turbofeed.state-cache.enabled:true}") boolean enabled,
                             @Value("${turbofeed.state-cache.ttl-seconds:15}") long ttlSeconds,
                             @Value("${turbofeed.state-cache.max-entries:100000}") int maxEntries) {
        this.enabled = enabled;
        this.ttlMillis = Math.max(0L, ttlSeconds) * 1000L;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** 信用等级（带缓存）。loader 只在未命中 / 已过期 / 缓存关闭时被调用。 */
    public CreditLevel level(long userId, Supplier<CreditLevel> loader) {
        return enabled ? getOrLoad(levelCache, userId, loader) : loader.get();
    }

    /** 可写闸门（带缓存）。语义与 {@code PenaltyService#canWrite} 完全一致。 */
    public boolean canWrite(long userId, Supplier<Boolean> loader) {
        return enabled ? Boolean.TRUE.equals(getOrLoad(writeCache, userId, loader)) : Boolean.TRUE.equals(loader.get());
    }

    /** 失效某账号的全部缓存项（本实例）。信用/处罚任一写入路径都应调用。 */
    public void invalidate(long userId) {
        levelCache.remove(userId);
        writeCache.remove(userId);
    }

    /** 缓存条目数（巡检 / 排障用）。 */
    public int size() {
        return levelCache.size() + writeCache.size();
    }

    private <T> T getOrLoad(ConcurrentHashMap<Long, Holder<T>> map, long userId, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Holder<T> hit = map.get(userId);
        if (hit != null && hit.expireAt > now) {
            return hit.value;
        }
        T loaded = loader.get();
        put(map, userId, new Holder<>(loaded, now + ttlMillis));
        return loaded;
    }

    private <T> void put(ConcurrentHashMap<Long, Holder<T>> map, long userId, Holder<T> holder) {
        if (map.size() >= maxEntries) {
            // 保护性熔断：整体清空（不是 LRU）——缓存是加速手段，绝不能成为内存风险源。
            map.clear();
            log.warn("账号状态缓存条目数达到上限 {}，已整体清空（退回全量读库）", maxEntries);
        }
        map.put(userId, holder);
    }

    /** 带过期时点的缓存条目（不可变）。 */
    private record Holder<T>(T value, long expireAt) {
    }
}
