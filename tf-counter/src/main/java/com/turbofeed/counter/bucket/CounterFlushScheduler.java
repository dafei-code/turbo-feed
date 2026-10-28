package com.turbofeed.counter.bucket;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * standalone 模式 flush 调度器（<b>默认</b>，{@code redis-buffer-enabled=false} / 缺省时生效）。
 *
 * <p>此模式下缓冲是各副本各自的进程内 {@code ConcurrentHashMap}（天然分片），每个副本独立 flush
 * 自己的那一份，<b>不需要也不该加 ShedLock</b>——加锁反而会让非 leader 副本的缓冲永不被 flush。
 * 因此本调度器只用普通 {@code @Scheduled}，无任何分布式锁。多副本并行 flush 各自的缓冲，
 * 互不重叠、互不重复，是正确且零开销的。</p>
 *
 * <p>切换到集群模式（共享 Redis 缓冲 + 选主）时，本 Bean 因条件不满足而不创建，
 * 由 {@link ClusteredCounterFlushScheduler} 接管。</p>
 */
@Component
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.COUNTER_REDIS_BUFFER_ENABLED, havingValue = "false", matchIfMissing = true)
public class CounterFlushScheduler {

    private final CounterFlushService flushService;

    public CounterFlushScheduler(CounterFlushService flushService) {
        this.flushService = flushService;
    }

    @Scheduled(fixedDelayString = "${turbofeed.counter.flush-interval-ms:5000}")
    public void flush() {
        flushService.flush();
    }
}
