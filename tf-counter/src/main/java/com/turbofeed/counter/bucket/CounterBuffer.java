package com.turbofeed.counter.bucket;

import com.turbofeed.counter.CounterType;

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
 * <p>契约要点：{@link #drain(String)} 必须是「取出快照并清空」的原子交接——同一份增量只会被
 * 一个 flush 周期消费一次（standalone 由单副本独占；集群由 ShedLock leader 独占）。</p>
 *
 * <p><b>PR-E 接口变更</b>：{@code drain} 现在接收由 flush 线程<b>提前生成</b>的 {@code batchId}，
 * 并返回 {@link CounterFlushSnapshot}（批次身份 + 增量快照）。这样落库失败时整份快照可以
 * 连同原 batchId 写入独立暂存、原样重放；若仍只返回裸 Map，失败后既没有身份也没有可持久化载体，
 * 而换新 batchId 重放会让累加式 UPSERT 重复计数。</p>
 */
public interface CounterBuffer {

    /** 累加某帖某维度增量。 */
    void add(String postId, CounterType type, long delta);

    /**
     * 取出当前全部增量快照并清空（原子交接给 flush 线程）。
     *
     * @param batchId 由 flush 线程在调用本方法<b>之前</b>生成的周期唯一标识，随快照一同交接
     */
    CounterFlushSnapshot drain(String batchId);

    /** 读取某帖当前仍在缓冲中（未落库）的增量；无则返回 {@code null}（flush 后修正 L1 用）。 */
    long[] get(String postId);

    /** 缓冲是否为空。 */
    boolean isEmpty();

    /**
     * 缓冲待落库负载快照（管理后台「计数分桶负载」面板用）。
     *
     * <p>返回当前仍在缓冲中（未落库）的帖子数与各维度增量合计。fail-open 语义由各实现保证：
     * 读 Redis 异常时返回全 0 的快照，不应抛给调用方。</p>
     */
    PendingStats pendingStats();

    /**
     * 缓冲负载快照。
     *
     * @param pendingPostCount 仍有待落库增量的帖子数（Redis 缓冲即 {@code SCARD tf:cb:keys}）
     * @param totalIncrements  各维度增量合计（可能为 0，负向增量会抵消）
     * @param like             点赞维度待落库增量合计
     * @param comment          评论维度待落库增量合计
     * @param share            分享维度待落库增量合计
     * @param commentLike      评论点赞维度待落库增量合计
     */
    record PendingStats(long pendingPostCount, long totalIncrements,
                        long like, long comment, long share, long commentLike) {
    }
}
