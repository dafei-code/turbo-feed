package com.turbofeed.counter.bucket;

import com.turbofeed.counter.CounterType;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计数增量<b>进程内</b>缓冲（削峰层，standalone 默认实现）。
 *
 * <p>网关每条点赞状态变化 → {@link #add} 计入内存（按 post_id 聚合到数组，
 * 下标对应 {@link CounterType} 序号）。{@link com.turbofeed.counter.bucket.CounterFlushScheduler}
 * 周期性 {@link #drain} 取出快照并清空，批量落 {@code post_counter}，把高频写转成低频批量写，
 * 消除「每行一写」的 DB 压力与潜在热点。</p>
 *
 * <p><b>单副本语义</b>：每个 tf-counter 副本各持一份 {@code ConcurrentHashMap}，彼此不共享。
 * 因此本实现仅适用于「每副本独立 flush 自己的缓冲」（standalone），不能配 ShedLock——否则
 * 非 leader 副本的缓冲永不被 flush。多副本共享场景请改用 {@link RedisCounterBuffer}（集群模式）。</p>
 *
 * <p>内存态是易失的：进程崩溃会丢未 flush 的增量（最多一个 flush-interval 窗口）。
 * 取舍与网关 Redis 计数同源——计数权威在 Redis，tf-counter 是聚合底座，最终一致可接受。</p>
 */
@Service
public class CounterBufferService implements CounterBuffer {

    /** post_id -> [likeDelta, commentDelta, shareDelta, commentLikeDelta]（下标 = CounterType 序号）。 */
    private final ConcurrentHashMap<String, long[]> buffer = new ConcurrentHashMap<>();

    /** 数组长度随 {@link CounterType} 维度数自动伸缩：新增维度无需改本类，避免新增类型后下标越界。 */
    private static final int DIM = CounterType.values().length;

    /** 累加某帖某维度增量。 */
    public void add(String postId, CounterType type, long delta) {
        if (postId == null || type == null || delta == 0) {
            return;
        }
        buffer.computeIfAbsent(postId, k -> new long[DIM])[type.ordinal()] += delta;
    }

    /** 取出当前全部增量快照并清空（原子交接给 flush 线程）。 */
    public Map<String, long[]> drain() {
        Map<String, long[]> snapshot = new HashMap<>(buffer);
        buffer.clear();
        return snapshot;
    }

    /** 读取某帖当前仍在缓冲中（未落库）的增量；无则返回 {@code null}。flush 后修正 L1 用。 */
    public long[] get(String postId) {
        return buffer.get(postId);
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }
}
