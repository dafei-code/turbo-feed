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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 缓冲定时批量落库核心逻辑（与调度器解耦，供 standalone / clustered 两种调度器共用）。
 *
 * <p>每周期：{@link CounterBuffer#drain()} 取增量快照 → 生成 {@code batch_id} →
 * {@link PostCounterRepository#applyBatch} 批量幂等落库 → 批量回读 {@code post_counter} →
 * 按「DB 权威值 + 窗口内新到达增量」修正 L1/L2，避免读路径短暂欠计数。</p>
 *
 * <p>另含一个低频 {@link #purgeStaleDedup} 调度，清理过期的去重记录（仅用于重放保护，
 * 超过 TTL 后保留无意义，避免表无限增长）。</p>
 *
 * <h3>M4 可观测性指标</h3>
 * <ul>
 *   <li>{@code tf_counter_flush_batch_size} —— 每周期落库的帖子数（DistributionSummary）；</li>
 *   <li>{@code tf_counter_flush_duration_seconds} —— 一个完整 flush 周期的耗时（含回读与 L1/L2 修正）；</li>
 *   <li>{@code tf_counter_flush_empty_total} —— 空快照周期数；</li>
 *   <li>{@code tf_counter_flush_dedup_skipped_total} —— 被 {@code counter_flush_dedup} 拦下的条数。</li>
 * </ul>
 *
 * <p><b>为什么空周期也要计数</b>：{@code batch_size} 在没有数据时不会有样本，
 * 于是「flush 一直在跑但推不出数据」和「调度根本没起来」在指标上完全一样。
 * 空周期计数是区分这两者的唯一依据——它持续增长说明调度正常、只是没有增量；
 * 它不动而 batch_size 也不动，才是调度挂了。</p>
 *
 * <p><b>{@code dedup_skipped} 的正常值是 0</b>。它一旦增长，说明整批重放路径被走到了
 * （同一 batch 被二次执行）——那条路径是为故障恢复准备的，平时不该出现。
 * 没有这个计数器，重放会安静地发生，谁也发现不了 flush 在反复失败重试。</p>
 */
@Service
public class CounterFlushService {

    private static final Logger log = LoggerFactory.getLogger(CounterFlushService.class);

    /** 去重记录保留时长（秒）：超过即不再可能重放，可清理。 */
    private static final long DEDUP_TTL_SECONDS = 3600L;

    private final CounterBuffer buffer;
    private final PostCounterRepository repo;
    private final CounterReadService readService;
    private final JdbcTemplate jdbcTemplate;

    private final Timer flushTimer;
    private final DistributionSummary batchSize;
    private final Counter emptyCycles;
    private final Counter dedupSkipped;

    public CounterFlushService(CounterBuffer buffer, PostCounterRepository repo,
                               CounterReadService readService, JdbcTemplate jdbcTemplate,
                               MeterRegistry registry) {
        this.buffer = buffer;
        this.repo = repo;
        this.readService = readService;
        this.jdbcTemplate = jdbcTemplate;
        this.flushTimer = Timer.builder("tf_counter_flush_duration_seconds")
                .description("一个完整 flush 周期耗时（drain + 落库 + 回读 + L1/L2 修正）")
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
    }

    /** 执行一次 flush 周期（standalone / clustered 调度器均调用此方法）。 */
    public void flush() {
        long start = System.nanoTime();
        try {
            Map<String, long[]> snapshot = buffer.drain();
            if (snapshot.isEmpty()) {
                emptyCycles.increment();
                return;
            }
            batchSize.record(snapshot.size());
            // 每周期唯一 batch_id：配合 counter_flush_dedup 实现整批幂等，重放安全。
            String batchId = UUID.randomUUID().toString();
            PostCounterRepository.ApplyResult result = repo.applyBatch(snapshot, batchId);
            if (result.dedupSkipped() > 0) {
                // 走到整批重放路径：说明同一个 batch_id 被投了两次（或上一轮实际已落但抛了异常）。
                // 结果仍然正确（dedup 拦住了），但「为什么会被重放」值得查——这里留痕 + 计数。
                dedupSkipped.increment(result.dedupSkipped());
                log.warn("flush 整批重放被去重拦下: batchId={}, 已落={}, 跳过={}",
                        batchId, result.applied(), result.dedupSkipped());
            }

            // flush 后修正 L1/L2：DB 权威值叠加窗口内新到达（未 drain）的增量，杜绝短暂欠计数
            Map<String, CounterView> dbMap = repo.getMany(snapshot.keySet());
            for (Map.Entry<String, long[]> e : snapshot.entrySet()) {
                String postId = e.getKey();
                CounterView db = dbMap.get(postId);
                if (db == null) {
                    db = CounterView.zero(postId);
                }
                long[] pending = buffer.get(postId);
                if (pending != null) {
                    db = db.add(pending[0], pending[1], pending[2], pending[3]);
                }
                readService.refresh(postId, db);
            }
        } finally {
            // 放在 finally：落库抛异常时这段耗时恰恰最有诊断价值（慢在哪一步），
            // 只在成功路径记时会把「已经失败的慢周期」从直方图里抹掉。
            flushTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** 清理过期去重记录（默认每 10 分钟一次，TTL 1 小时）。 */
    @Scheduled(fixedDelayString = "${turbofeed.counter.dedup-purge-interval-ms:600000}")
    public void purgeStaleDedup() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(DEDUP_TTL_SECONDS));
        jdbcTemplate.update("DELETE FROM counter_flush_dedup WHERE created_at < ?", cutoff);
    }
}
