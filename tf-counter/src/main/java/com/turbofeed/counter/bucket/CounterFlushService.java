package com.turbofeed.counter.bucket;

import com.turbofeed.counter.cache.CounterReadService;
import com.turbofeed.counter.persist.PostCounterRepository;
import com.turbofeed.shared.model.CounterView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 缓冲定时批量落库核心逻辑（与调度器解耦，供 standalone / clustered 两种调度器共用）。
 *
 * <p>每周期：{@link CounterBuffer#drain()} 取增量快照 → 生成 {@code batch_id} →
 * {@link PostCounterRepository#applyBatch} 批量幂等落库 → 批量回读 {@code post_counter} →
 * 按「DB 权威值 + 窗口内新到达增量」修正 L1/L2，避免读路径短暂欠计数。</p>
 *
 * <p>另含一个低频 {@link #purgeStaleDedup} 调度，清理过期的去重记录（仅用于重放保护，
 * 超过 TTL 后保留无意义，避免表无限增长）。</p>
 */
@Service
public class CounterFlushService {

    /** 去重记录保留时长（秒）：超过即不再可能重放，可清理。 */
    private static final long DEDUP_TTL_SECONDS = 3600L;

    private final CounterBuffer buffer;
    private final PostCounterRepository repo;
    private final CounterReadService readService;
    private final JdbcTemplate jdbcTemplate;

    public CounterFlushService(CounterBuffer buffer, PostCounterRepository repo,
                               CounterReadService readService, JdbcTemplate jdbcTemplate) {
        this.buffer = buffer;
        this.repo = repo;
        this.readService = readService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 执行一次 flush 周期（standalone / clustered 调度器均调用此方法）。 */
    public void flush() {
        Map<String, long[]> snapshot = buffer.drain();
        if (snapshot.isEmpty()) {
            return;
        }
        // 每周期唯一 batch_id：配合 counter_flush_dedup 实现整批幂等，重放安全。
        String batchId = UUID.randomUUID().toString();
        repo.applyBatch(snapshot, batchId);

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
                db = db.add(pending[0], pending[1], pending[2]);
            }
            readService.refresh(postId, db);
        }
    }

    /** 清理过期去重记录（默认每 10 分钟一次，TTL 1 小时）。 */
    @Scheduled(fixedDelayString = "${turbofeed.counter.dedup-purge-interval-ms:600000}")
    public void purgeStaleDedup() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(DEDUP_TTL_SECONDS));
        jdbcTemplate.update("DELETE FROM counter_flush_dedup WHERE created_at < ?", cutoff);
    }
}
