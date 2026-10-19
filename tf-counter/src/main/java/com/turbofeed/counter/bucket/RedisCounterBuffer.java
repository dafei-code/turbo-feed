package com.turbofeed.counter.bucket;

import com.turbofeed.counter.CounterType;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计数增量<b>共享 Redis</b>缓冲（削峰层，集群模式实现）。
 *
 * <p>激活条件：<b>同时</b> {@code turbofeed.counter.redis-buffer.enabled=true} 且
 * {@code turbofeed.counter.redis.enabled=true}（需 RedissonClient）。激活后本 Bean 标
 * {@link Primary}，覆盖进程内 {@link CounterBufferService}，成为注入 {@link CounterBuffer}
 * 的实际实现。</p>
 *
 * <p><b>多副本正确性</b>：所有 tf-counter 副本把增量写进同一份 Redis 缓冲，因此没有任何副本
 * 「私藏」增量。再配合 {@code ClusteredCounterFlushScheduler} 的 ShedLock 选主，保证同一时刻
 * 只有一个副本执行 {@link #drain()} 落库——既不会丢（非 leader 的增量也已入共享缓冲），
 * 也不会重（leader 独占 drain，且 {@code applyBatch} 用 batch_id 去重）。</p>
 *
 * <p><b>数据模型</b>：每帖三个原子计数 {@code tf:cb:{postId}:like|comment|share}（INCRBY 累加，
 * 支持负增量），并用集合 {@code tf:cb:keys} 记录「哪些帖有待落库增量」。{@code add} 与
 * {@code drain} 均走 Lua 脚本，保证原子性与单往返。</p>
 *
 * <p><b>drain 原子性</b>：脚本内 SMEMBERS→逐帖读取→DEL+ SREM 全在一段 Lua 中执行，Redis 保证
 * 脚本执行期间无其他命令穿插，因此不会丢「drain 过程中新到达的 add」（新 add 要么被本次
 * 读到，要么留在新键里等下个周期）。</p>
 */
@Component
@ConditionalOnProperty(name = {
        "turbofeed.counter.redis-buffer.enabled",
        "turbofeed.counter.redis.enabled"
}, havingValue = "true")
@Primary
public class RedisCounterBuffer implements CounterBuffer {

    private static final String KEYSET_KEY = "tf:cb:keys";

    /** add：SADD 登记帖 + INCRBY 累加对应维度（单脚本、原子、单往返）。 */
    private static final String ADD_LUA =
            "redis.call('SADD', KEYS[1], ARGV[1]); "
          + "redis.call('INCRBY', 'tf:cb:'..ARGV[1]..ARGV[2], ARGV[3]); return 1";

    /** drain：取出全部待落库增量快照并清空（单脚本、原子、单往返）。
     *  返回扁平列表 [postId, like, comment, share, postId2, ...]，缺失维度按 '0'。 */
    private static final String DRAIN_LUA =
            "local keys = redis.call('SMEMBERS', KEYS[1]); local out = {}; "
          + "for i=1,#keys do local p = keys[i]; "
          + "local like = redis.call('GET', 'tf:cb:'..p..':like'); "
          + "local comment = redis.call('GET', 'tf:cb:'..p..':comment'); "
          + "local share = redis.call('GET', 'tf:cb:'..p..':share'); "
          + "table.insert(out, p); table.insert(out, like or '0'); "
          + "table.insert(out, comment or '0'); table.insert(out, share or '0'); "
          + "redis.call('DEL', 'tf:cb:'..p..':like', 'tf:cb:'..p..':comment', 'tf:cb:'..p..':share'); "
          + "redis.call('SREM', KEYS[1], p); end; return out";

    private final RedissonClient redisson;

    public RedisCounterBuffer(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public void add(String postId, CounterType type, long delta) {
        if (postId == null || type == null || delta == 0) {
            return;
        }
        // Lua 内先 SADD 再 INCRBY：即使进程在两者之间崩溃，最坏也只是该增量丢失（≤1 窗口），
        // 不会出现「已累加却不在 keyset 里永不被 drain」的泄漏。
        redisson.getScript().eval(RScript.Mode.READ_WRITE, ADD_LUA, RScript.ReturnType.INTEGER,
                List.of(KEYSET_KEY), postId, suffix(type), Long.toString(delta));
    }

    @Override
    public Map<String, long[]> drain() {
        List<Object> raw = redisson.getScript().eval(RScript.Mode.READ_WRITE, DRAIN_LUA,
                RScript.ReturnType.MULTI, List.of(KEYSET_KEY));
        Map<String, long[]> snapshot = new HashMap<>();
        for (int i = 0; i + 3 < raw.size(); i += 4) {
            String postId = String.valueOf(raw.get(i));
            long like = parse(raw.get(i + 1));
            long comment = parse(raw.get(i + 2));
            long share = parse(raw.get(i + 3));
            snapshot.put(postId, new long[]{like, comment, share});
        }
        return snapshot;
    }

    @Override
    public long[] get(String postId) {
        // 非破坏性读取：供 flush 后「DB 权威值 + 窗口内新到达增量」修正 L1/L2。
        long like = redisson.getAtomicLong("tf:cb:" + postId + ":like").get();
        long comment = redisson.getAtomicLong("tf:cb:" + postId + ":comment").get();
        long share = redisson.getAtomicLong("tf:cb:" + postId + ":share").get();
        if (like == 0 && comment == 0 && share == 0) {
            return null;
        }
        return new long[]{like, comment, share};
    }

    @Override
    public boolean isEmpty() {
        return redisson.getSet(KEYSET_KEY).size() == 0;
    }

    private static String suffix(CounterType type) {
        return switch (type) {
            case LIKE -> ":like";
            case COMMENT -> ":comment";
            case SHARE -> ":share";
        };
    }

    private static long parse(Object o) {
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
