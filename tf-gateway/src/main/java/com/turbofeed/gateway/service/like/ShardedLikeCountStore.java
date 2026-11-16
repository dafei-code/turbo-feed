package com.turbofeed.gateway.service.like;

import com.turbofeed.shared.feature.FeatureFlagKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 热点帖计数 Key 分槽（评审优化项③，<b>默认关闭</b>）。
 *
 * <h3>为什么会有这个类</h3>
 * <p>原设计刻意不打散计数 Key：它只存一个整数，{@code INCR} 恒 O(1)，不像去重 Set 有元素膨胀，
 * 打散会把「读一次点赞数」放大成 64 个 Key。但在爆款单帖万级集中流量下，单 Key 的
 * {@code INCR/DECR/GET} 全部落在同一个 Redis slot，热点节点 CPU 会先打满。
 * 本类提供 opt-in 的分槽形态，把写按 userId 散到 64 个不同 slot。</p>
 *
 * <h3>数据模型（base + 64 槽净增量）</h3>
 * <ul>
 *   <li>{@code <legacyPrefix><id>:s:base} —— 开启分槽前旧单 Key 的一次性快照（SETNX 迁移）；</li>
 *   <li>{@code <legacyPrefix><id>:s:{0..63}} —— 各槽<b>净增量</b>（可正可负），
 *       槽号 = {@code floorMod(Long.hashCode(userId), 64)}；</li>
 *   <li>真实总数 = {@code base + Σ(64 槽)}。</li>
 * </ul>
 *
 * <p><b>为什么用「净增量」而不是各槽存绝对值</b>：{@code INCRBY} 对不存在的 Key 从 0 起算，
 * 正好是增量语义，天然不需要旧单 Key 版那套「Key 缺失时用 64 槽 SCARD 重算」的冷启动校正；
 * 取消赞让某槽变成 -1 也是合法且可逆的。</p>
 *
 * <p><b>Key 不加 hash tag</b>：故意让 64 个槽散到不同 Redis slot（这才是打散热点的目的）。
 * 因此不能用跨 Key Lua（CROSSSLOT），改用 {@code executePipelined} 把读聚合成一次往返；
 * 单槽 {@code INCRBY} 本身原子，读是最终一致快照，不要求跨槽事务。</p>
 *
 * <h3>读放大（开前必须确认）</h3>
 * <p>单条读：1 次 GET base + 1 次 pipeline（65 个 GET）；列表页 N 条：
 * 1 次 pipeline 共 {@code N × 65} 个 GET，命令数比单 Key MGET 放大约 64 倍。
 * 只在热点 slot CPU 成为实测瓶颈、且 Redis 集群有余量时开启。</p>
 *
 * <h3>回退</h3>
 * <p>关闭开关前<b>必须</b>对每个有分槽数据的帖子调用 {@link #foldBack}：
 * 把 {@code base + Σ槽} 写回旧单 Key 并删除分槽数据，否则关闭后读路径会退回
 * 「开启前的旧快照」，丢掉开启期间的全部增量。步骤见 docs/ops/hotspot-count-sharding.md。</p>
 */
@Component
public class ShardedLikeCountStore {

    private static final Logger log = LoggerFactory.getLogger(ShardedLikeCountStore.class);

    public static final int SLOTS = 64;
    /** 与单 Key 版 COUNT_TTL 对齐；每次 bump 会续期 base 与被触碰的槽。 */
    private static final Duration TTL = Duration.ofDays(30);
    private static final String BASE_SUFFIX = ":s:base";
    private static final String SHARD_SUFFIX = ":s:";

    private final StringRedisTemplate redis;
    private final boolean enabled;

    public ShardedLikeCountStore(StringRedisTemplate redis,
                                 @Value("${" + FeatureFlagKeys.LIKE_COUNT_SHARDING_ENABLED + ":false}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    static String baseKey(String legacyPrefix, String id) {
        return legacyPrefix + id + BASE_SUFFIX;
    }

    static String shardKey(String legacyPrefix, String id, int slot) {
        return legacyPrefix + id + SHARD_SUFFIX + slot;
    }

    static int slotOf(long userId) {
        return Math.floorMod(Long.hashCode(userId), SLOTS);
    }

    /**
     * 写：按 userId 归属槽累加净增量。首次访问先把旧单 Key 快照迁进 base。
     * base 与槽同寿，每次写都续期，保证「仍活跃的帖子」不会因 TTL 丢增量。
     */
    public void bump(String legacyPrefix, String id, long userId, int delta) {
        ensureBase(legacyPrefix, id);
        byte[] shard = shardKey(legacyPrefix, id, slotOf(userId)).getBytes(StandardCharsets.UTF_8);
        byte[] base = baseKey(legacyPrefix, id).getBytes(StandardCharsets.UTF_8);
        long ttlSeconds = TTL.getSeconds();
        redis.executePipelined((RedisCallback<Object>) connection -> {
            connection.stringCommands().incrBy(shard, delta);
            connection.keyCommands().expire(shard, ttlSeconds);
            connection.keyCommands().expire(base, ttlSeconds);
            return null;
        });
    }

    /** 单条读：一次 pipeline 取 base + 64 槽，聚合成总数。 */
    public Long read(String legacyPrefix, String id) {
        ensureBase(legacyPrefix, id);
        byte[] base = baseKey(legacyPrefix, id).getBytes(StandardCharsets.UTF_8);
        List<byte[]> keys = new ArrayList<>(SLOTS + 1);
        keys.add(base);
        for (int slot = 0; slot < SLOTS; slot++) {
            keys.add(shardKey(legacyPrefix, id, slot).getBytes(StandardCharsets.UTF_8));
        }
        // 详情页读路径顺带续期：base 与 64 槽同寿，避免「长期只读不写」的帖子 30 天后丢净增量。
        // 列表批量路径（readMany）刻意不续期，否则会把已经 64 倍的读放大再翻倍。
        try {
            long ttlSeconds = TTL.getSeconds();
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (byte[] k : keys) {
                    connection.keyCommands().expire(k, ttlSeconds);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("分槽计数 TTL 续期失败（退化为固定 TTL）: id={}, {}", id, e.toString());
        }
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (byte[] k : keys) {
                connection.stringCommands().get(k);
            }
            return null;
        });
        return aggregate(results, 0);
    }

    /**
     * 批量读：所有 id 的 base + 64 槽合并进<b>一个</b> pipeline（一次往返），
     * 结果按 {@code 项序 × (SLOTS+1)} 连续排列，索引天然对齐。
     */
    public Map<String, Long> readMany(String legacyPrefix, Collection<String> ids) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        List<String> list = new ArrayList<>(ids);
        ensureBases(legacyPrefix, list);
        List<byte[]> keys = new ArrayList<>(list.size() * (SLOTS + 1));
        for (String id : list) {
            keys.add(baseKey(legacyPrefix, id).getBytes(StandardCharsets.UTF_8));
            for (int slot = 0; slot < SLOTS; slot++) {
                keys.add(shardKey(legacyPrefix, id, slot).getBytes(StandardCharsets.UTF_8));
            }
        }
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (byte[] k : keys) {
                connection.stringCommands().get(k);
            }
            return null;
        });
        for (int i = 0; i < list.size(); i++) {
            out.put(list.get(i), aggregate(results, i * (SLOTS + 1)));
        }
        return out;
    }

    /** 对账：用去重集合（幂等权威）算出的总数覆盖 base，并清空 64 槽净增量。 */
    public void recount(String legacyPrefix, String id, long authoritativeCount) {
        redis.opsForValue().set(baseKey(legacyPrefix, id), String.valueOf(authoritativeCount), TTL);
        redis.delete(shardKeys(legacyPrefix, id));
    }

    /**
     * 回退到单 Key：把 {@code base + Σ槽} 写回旧单 Key，再删除全部分槽数据。
     * 必须在关闭 {@code turbofeed.like.count-sharding-enabled} 之前对每个活跃帖执行。
     *
     * @return 回灌后的总数
     */
    public long foldBack(String legacyPrefix, String id) {
        Long total = read(legacyPrefix, id);
        long value = total == null ? 0L : total;
        redis.opsForValue().set(legacyPrefix + id, String.valueOf(value), TTL);
        List<String> toDelete = shardKeys(legacyPrefix, id);
        toDelete.add(baseKey(legacyPrefix, id));
        redis.delete(toDelete);
        log.info("分槽计数已回灌单 Key: id={}, total={}", id, value);
        return value;
    }

    /** 聚合纯函数（包可见，便于单测锁定索引对齐，不依赖 Redis）。 */
    static long aggregate(List<Object> results, int offset) {
        if (results == null) {
            return 0L;
        }
        long total = number(results, offset);
        for (int slot = 0; slot < SLOTS; slot++) {
            total += number(results, offset + 1 + slot);
        }
        return total;
    }

    private static long number(List<Object> results, int index) {
        if (index >= results.size()) {
            return 0L;
        }
        Object v = results.get(index);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof byte[] b) {
            return parseLong(new String(b, StandardCharsets.UTF_8));
        }
        return parseLong(String.valueOf(v));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 首访迁移：旧单 Key 的值作为 base 快照，SETNX 防止并发覆盖已有 base。 */
    private void ensureBase(String legacyPrefix, String id) {
        String base = baseKey(legacyPrefix, id);
        if (redis.opsForValue().get(base) != null) {
            return;
        }
        String legacy = redis.opsForValue().get(legacyPrefix + id);
        long seed = legacy == null ? 0L : parseLong(legacy);
        redis.opsForValue().setIfAbsent(base, String.valueOf(seed), TTL);
    }

    /** 批量读前一次性探测 base，只有真正缺失的 id 才走迁移（热路径不逐帖多打 RTT）。 */
    private void ensureBases(String legacyPrefix, List<String> ids) {
        List<Object> existing = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String id : ids) {
                connection.stringCommands().get(baseKey(legacyPrefix, id).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        if (existing == null) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (i < existing.size() && existing.get(i) == null) {
                ensureBase(legacyPrefix, ids.get(i));
            }
        }
    }

    private List<String> shardKeys(String legacyPrefix, String id) {
        List<String> keys = new ArrayList<>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) {
            keys.add(shardKey(legacyPrefix, id, slot));
        }
        return keys;
    }
}
