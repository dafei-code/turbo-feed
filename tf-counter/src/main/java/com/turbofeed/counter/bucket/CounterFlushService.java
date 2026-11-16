package com.turbofeed.counter.bucket;

import com.turbofeed.counter.cache.CounterReadService;
import com.turbofeed.counter.persist.PostCounterRepository;
import com.turbofeed.shared.model.CounterView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 缓冲定时批量落库核心逻辑（与调度器解耦，供 standalone / clustered 两种调度器共用）。
 *
 * <h3>PR-E：三段式交接（先身份 → 再交接 → 后落库，失败即暂存）</h3>
 * <ol>
 *   <li><b>先重放</b>：每周期开头把 {@code counter_flush_pending} 里的历史失败批次用
 *       <b>原 batchId</b> 重放，成功即删除。重启后第一个周期也会执行，覆盖崩溃窗口。</li>
 *   <li><b>先生成 batchId</b>，再 {@link CounterBuffer#drain(String)} 原子取出
 *       {@link CounterFlushSnapshot}（批次身份 + 增量快照同生共死）。</li>
 *   <li><b>落库失败即暂存</b>：整份快照连同原 batchId 写入独立失败暂存，绝不还回热缓冲；
 *       下个周期用同一 batchId 重放，命中 {@code counter_flush_dedup} 才是真幂等。</li>
 * </ol>
 *
 * <p><b>为什么不能还回热缓冲</b>：缓冲没有批次身份，且 {@code applyBatch} 是
 * {@code +VALUES()} 累加 UPSERT。还回去会用新 batchId 再落一次 → 重复计数。
 * 改造前 {@code drain()} 后直接 {@code applyBatch}、异常只被 finally 记录耗时，
 * 快照既不在缓冲也不在库，突发期 DB 抖动时增量静默永久丢失。</p>
 *
 * <p>成功落库后批量回读 {@code post_counter}，按「DB 权威值 + 窗口内新到达增量」修正 L1/L2。
 * <b>这一步的异常单独兜住</b>：数据已经落库，若让它冒泡把整批再存进暂存，就会重复累加。</p>
 *
 * <p>另含一个低频 {@link #purgeStaleDedup} 调度，清理过期去重记录。</p>
 *
 * <h3>M4 可观测性指标</h3>
 * <ul>
 *   <li>{@code tf_counter_flush_batch_size} —— 每周期落库的帖子数；</li>
 *   <li>{@code tf_counter_flush_duration_seconds} —— 一个完整 flush 周期的耗时；</li>
 *   <li>{@code tf_counter_flush_empty_total} —— 空快照周期数（区分「无增量」与「调度挂了」）；</li>
 *   <li>{@code tf_counter_flush_dedup_skipped_total} —— 被 dedup 拦下的条数；</li>
 *   <li>{@code tf_counter_flush_failed_total} / {@code tf_counter_flush_pending_saved_total}
 *       / {@code tf_counter_flush_replayed_total} —— 失败、暂存、重放三个阶段的计数。</li>
 * </ul>
 */
@Service
public class CounterFlushService {

    private static final Logger log = LoggerFactory.getLogger(CounterFlushService.class);

    /** 去重记录保留时长（秒）：超过即不再可能重放，可清理。 */
    private static final long DEDUP_TTL_SECONDS = 3600L;

    /** 单周期最多重放的失败批次数：避免积压时把一次 flush 卡死，剩余批次下周期继续。 */
    private static final int REPLAY_LIMIT_PER_CYCLE = 20;

    private final CounterBuffer buffer;
    private final PostCounterRepository repo;
    private final CounterReadService readService;
    private final JdbcTemplate jdbcTemplate;
    private final CounterFlushPendingStore pendingStore;

    private final Timer flushTimer;
    private final DistributionSummary batchSize;
    private final Counter emptyCycles;
    private final Counter dedupSkipped;
    private final Counter failedCycles;
    private final Counter pendingSaved;
    private final Counter replayedBatches;

    public CounterFlushService(CounterBuffer buffer, PostCounterRepository repo,
                               CounterReadService readService, JdbcTemplate jdbcTemplate,
                               CounterFlushPendingStore pendingStore, MeterRegistry registry) {
        this.buffer = buffer;
        this.repo = repo;
        this.readService = readService;
        this.jdbcTemplate = jdbcTemplate;
        this.pendingStore = pendingStore;
        this.flushTimer = Timer.builder("tf_counter_flush_duration_seconds")
                .description("一个完整 flush 周期耗时（重放 + drain + 落库 + 回读 + L1/L2 修正）")
                .register(registry);
        this.batchSize = DistributionSummary.builder("tf_counter_flush_batch_size")
                .description("每周期落库的帖子数")
                .register(registry);
        this.emptyCycles = Counter.builder("tf_counter_flush_empty_total")
                .description("空快照周期数（区分「调度正常但无增量」与「调度挂了」）")
                .register(registry);
        this.dedupSkipped = Counter.builder("tf_counter_flush_dedup_skipped_total")
                .description("被 counter_flush_dedup 拦下的条数（>0 说明走到了整批重放路径）")
                .register(registry);
        this.failedCycles = Counter.builder("tf_counter_flush_failed_total")
                .description("落库失败周期数（快照已转失败暂存）")
                .register(registry);
        this.pendingSaved = Counter.builder("tf_counter_flush_pending_saved_total")
                .description("写入独立失败暂存的批次数")
                .register(registry);
        this.replayedBatches = Counter.builder("tf_counter_flush_replayed_total")
                .description("用原 batchId 重放成功的批次数")
                .register(registry);
    }

    /** 执行一次 flush 周期（standalone / clustered 调度器均调用此方法）。 */
    public void flush() {
        long start = System.nanoTime();
        try {
            replayPending();

            // ① batchId 必须在 drain 之前生成：失败时才有身份可随快照一起持久化。
            String batchId = UUID.randomUUID().toString();
            // ② 原子交接：缓冲清空的同时，快照与 batchId 一起交给本周期。
            CounterFlushSnapshot snapshot = buffer.drain(batchId);
            if (snapshot == null || snapshot.isEmpty()) {
                emptyCycles.increment();
                return;
            }
            batchSize.record(snapshot.size());
            // ③ 落库；任何失败都转成「整批暂存」，不再把异常丢给调度器后丢掉快照。
            try {
                applySnapshot(snapshot);
            } catch (Exception e) {
                persistFailed(snapshot, e);
            }
        } finally {
            // 放在 finally：落库抛异常时这段耗时恰恰最有诊断价值。
            flushTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** 用<b>原 batchId</b> 重放历史失败批次；成功即删暂存，失败只累加次数、保留快照。 */
    private void replayPending() {
        List<CounterFlushPendingStore.PendingBatch> batches;
        try {
            batches = pendingStore.loadOldest(REPLAY_LIMIT_PER_CYCLE);
        } catch (Exception e) {
            log.error("失败暂存读取失败（下个周期继续重试，不影响本周期新增量）", e);
            return;
        }
        if (batches == null || batches.isEmpty()) {
            return;
        }
        for (CounterFlushPendingStore.PendingBatch b : batches) {
            try {
                applySnapshot(b.snapshot());
                pendingStore.delete(b.batchId());
                replayedBatches.increment();
                log.warn("失败批次重放成功（复用原 batchId，dedup 防重复累加）: batchId={}, 历史失败次数={}, posts={}",
                        b.batchId(), b.attempts(), b.snapshot().size());
            } catch (Exception e) {
                try {
                    pendingStore.touch(b.batchId(), String.valueOf(e));
                } catch (Exception touchEx) {
                    log.error("失败批次重放失败且次数回写也失败: batchId={}", b.batchId(), touchEx);
                }
                log.error("失败批次重放仍失败（快照保留，下周期继续）: batchId={}, 历史失败次数={}",
                        b.batchId(), b.attempts(), e);
            }
        }
    }

    /** 落库 + 回读修正。仅 {@code applyBatch} 的异常会向上传播（触发暂存）。 */
    private void applySnapshot(CounterFlushSnapshot snapshot) {
        PostCounterRepository.ApplyResult result = repo.applyBatch(snapshot.deltas(), snapshot.batchId());
        if (result == null) {
            result = PostCounterRepository.ApplyResult.EMPTY;
        }
        if (result.dedupSkipped() > 0) {
            dedupSkipped.increment(result.dedupSkipped());
            log.warn("flush 整批重放被去重拦下: batchId={}, 已落={}, 跳过={}",
                    snapshot.batchId(), result.applied(), result.dedupSkipped());
        }
        refreshCaches(snapshot.deltas());
    }

    /**
     * L1/L2 修正单独兜异常：此刻数据已落库，若让异常冒泡把整批再存暂存，
     * 下周期重放会重复累加。缓存短暂旧值可接受，读路径最终会被下一次刷新纠正。
     */
    private void refreshCaches(Map<String, long[]> deltas) {
        try {
            Map<String, CounterView> dbMap = repo.getMany(deltas.keySet());
            for (Map.Entry<String, long[]> e : deltas.entrySet()) {
                String postId = e.getKey();
                CounterView db = dbMap.getOrDefault(postId, CounterView.zero(postId));
                long[] pending = buffer.get(postId);
                if (pending != null) {
                    db = db.add(pending[0], pending[1], pending[2], pending[3]);
                }
                readService.refresh(postId, db);
            }
        } catch (Exception e) {
            log.warn("flush 后 L1/L2 修正失败（数据已落库，不重放本批）: posts={}, {}",
                    deltas.size(), e.toString());
        }
    }

    /** 失败快照落独立暂存；暂存本身写失败（通常意味着同一个 DB 也挂了）必须 ERROR 留痕。 */
    private void persistFailed(CounterFlushSnapshot snapshot, Exception cause) {
        failedCycles.increment();
        try {
            pendingStore.save(snapshot, String.valueOf(cause));
            pendingSaved.increment();
            log.error("flush 落库失败，整批快照已存失败暂存（原 batchId 待重放）: batchId={}, posts={}",
                    snapshot.batchId(), snapshot.size(), cause);
        } catch (Exception saveEx) {
            log.error("flush 落库失败且失败暂存写入也失败（需人工对账）: batchId={}, posts={}, cause={}",
                    snapshot.batchId(), snapshot.size(), String.valueOf(cause), saveEx);
        }
    }

    /** 清理过期去重记录（默认每 10 分钟一次，TTL 1 小时）。 */
    @Scheduled(fixedDelayString = "${turbofeed.counter.dedup-purge-interval-ms:600000}")
    public void purgeStaleDedup() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(DEDUP_TTL_SECONDS));
        jdbcTemplate.update("DELETE FROM counter_flush_dedup WHERE created_at < ?", cutoff);
    }
}
