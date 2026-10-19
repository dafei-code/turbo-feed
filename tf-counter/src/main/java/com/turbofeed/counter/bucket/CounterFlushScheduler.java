package com.turbofeed.counter.bucket;

import com.turbofeed.counter.cache.CounterReadService;
import com.turbofeed.counter.persist.PostCounterRepository;
import com.turbofeed.shared.model.CounterView;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内存缓冲定时批量落库（最终一致落库层）。
 *
 * <p>周期性 {@link CounterBufferService#drain} 取出增量快照 → 逐帖
 * {@link PostCounterRepository#applyDelta} 批量落 {@code post_counter}（低频批量写，无热点行锁）。
 * flush 后按「DB 权威值 + 窗口内新到达的未落库增量」修正 L1/L2，避免读路径短暂欠计数。</p>
 */
@Component
public class CounterFlushScheduler {

    private final CounterBufferService buffer;
    private final PostCounterRepository repo;
    private final CounterReadService readService;

    public CounterFlushScheduler(CounterBufferService buffer, PostCounterRepository repo,
                                  CounterReadService readService) {
        this.buffer = buffer;
        this.repo = repo;
        this.readService = readService;
    }

    @Scheduled(fixedDelayString = "${turbofeed.counter.flush-interval-ms:5000}")
    public void flush() {
        Map<String, long[]> snapshot = buffer.drain();
        if (snapshot.isEmpty()) {
            return;
        }
        for (Map.Entry<String, long[]> e : snapshot.entrySet()) {
            long[] d = e.getValue();
            repo.applyDelta(e.getKey(), d[0], d[1], d[2]);
        }
        // flush 后修正 L1/L2：DB 权威值叠加窗口内新到达（未 drain）的增量，杜绝短暂欠计数
        for (String postId : snapshot.keySet()) {
            CounterView db = repo.get(postId);
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
}
