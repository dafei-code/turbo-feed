package com.turbofeed.counter.bucket;

import java.util.Map;

/**
 * flush 周期的原子交接载体：<b>batchId 与增量快照同生共死</b>（PR-E B2 三段式）。
 *
 * <p>改造前 {@code drain()} 只返回 {@code Map<String,long[]>}，batchId 是 drain 之后才生成的，
 * 于是「drain 已清空缓冲 → applyBatch 失败」之间没有任何可持久化的身份：</p>
 * <pre>
 *   drain() 清空缓冲 ──▶ applyBatch 失败 ──▶ 快照既不在缓冲、也不在库、也没有 batchId
 *                                          ↓
 *                              增量永久丢失（Redis 已可见、post_counter 永远少记）
 * </pre>
 *
 * <p>现在 batchId 由 flush 线程在 drain <b>之前</b>生成并随快照一并交接：
 * 失败时可以整份写入 {@code counter_flush_pending}，重放时<b>复用原 batchId</b> 调
 * {@code applyBatch}，命中 {@code counter_flush_dedup} 才是真正的幂等。
 * 若把失败快照还回热缓冲、下周期换新 batchId，累加式 UPSERT 会重复计数。</p>
 *
 * @param batchId 本 flush 周期唯一标识（UUID），重放时必须复用
 * @param deltas  postId -> [like, comment, share, commentLike]
 */
public record CounterFlushSnapshot(String batchId, Map<String, long[]> deltas) {

    public CounterFlushSnapshot {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("flush 快照必须带 batchId，否则无法原样重放");
        }
        deltas = deltas == null ? Map.of() : deltas;
    }

    public boolean isEmpty() {
        return deltas.isEmpty();
    }

    public int size() {
        return deltas.size();
    }

    public static CounterFlushSnapshot empty(String batchId) {
        return new CounterFlushSnapshot(batchId, Map.of());
    }
}
