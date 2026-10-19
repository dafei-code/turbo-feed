package com.turbofeed.counter.bucket;

import com.turbofeed.counter.CounterType;

import java.util.Map;

/**
 * 计数增量缓冲抽象（削峰层契约）。
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@link CounterBufferService}：进程内 {@code ConcurrentHashMap}，<b>standalone 默认</b>。
 *       每副本各持一份（天然分片，无跨副本重复），适合单实例或每副本独立 flush 的部署。</li>
 *   <li>{@link RedisCounterBuffer}：共享 Redis 缓冲，<b>集群模式</b>
 *       （{@code redis-buffer.enabled=true}）。所有副本写同一份，配合 ShedLock 选主独占 drain，
 *       多副本下也不丢、不重。</li>
 * </ul>
 * </p>
 *
 * <p>契约要点：{@link #drain()} 必须是「取出快照并清空」的原子交接——同一份增量只会被
 * 一个 flush 周期消费一次（standalone 由单副本独占；集群由 ShedLock leader 独占）。</p>
 */
public interface CounterBuffer {

    /** 累加某帖某维度增量。 */
    void add(String postId, CounterType type, long delta);

    /** 取出当前全部增量快照并清空（原子交接给 flush 线程）。 */
    Map<String, long[]> drain();

    /** 读取某帖当前仍在缓冲中（未落库）的增量；无则返回 {@code null}（flush 后修正 L1 用）。 */
    long[] get(String postId);

    /** 缓冲是否为空。 */
    boolean isEmpty();
}
