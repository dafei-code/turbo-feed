package com.turbofeed.counter.service;

import com.turbofeed.counter.CounterType;
import com.turbofeed.counter.bucket.CounterBuffer;
import com.turbofeed.counter.cache.CounterReadService;
import com.turbofeed.shared.model.CounterIncrementEvent;
import org.springframework.stereotype.Service;

/**
 * 计数增量接收（HTTP / MQ 两条通道汇流于此）。
 *
 * <p>{@link #accept} 把一条增量写入内存缓冲（供定时 flush 落库），并乐观更新 L1 读缓存，
 * 使同实例内读立即可见。不做任何远程调用、不抛异常，保证接收路径极轻、fail-open。</p>
 */
@Service
public class CounterIncrService {

    private final CounterBuffer buffer;
    private final CounterReadService readService;

    public CounterIncrService(CounterBuffer buffer, CounterReadService readService) {
        this.buffer = buffer;
        this.readService = readService;
    }

    public void accept(CounterIncrementEvent e) {
        if (e == null || e.postId() == null || e.type() == null) {
            return;
        }
        CounterType t = CounterType.of(e.type());
        if (t == null) {
            return;
        }
        long d = e.delta();
        buffer.add(e.postId(), t, d);
        // 乐观更新 L1：同实例内读立即可见（flush 后再由 DB 权威值修正）
        readService.applyLiveDelta(e.postId(),
                t == CounterType.LIKE ? d : 0,
                t == CounterType.COMMENT ? d : 0,
                t == CounterType.SHARE ? d : 0);
    }
}
