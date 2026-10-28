package com.turbofeed.counter.bucket;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 集群模式 flush 调度器（{@code redis-buffer-enabled=true} 时生效，覆盖 standalone 调度器）。
 *
 * <p>此模式下缓冲是<b>共享 Redis 缓冲</b>，所有副本写同一份。因此必须保证「同一时刻只有一个副本
 * 执行 drain」——否则多副本各自 drain 同一份共享缓冲会重复落库。{@link SchedulerLock} 用 ShedLock
 * 选主，名字 {@code tf-counter-flush} 在多副本间互斥；非 leader 副本本周期直接跳过，其增量已落入
 * 共享缓冲、由 leader 统一落库，<b>不丢不重</b>。</p>
 *
 * <p>{@code lockAtMostFor=9s} 兜住任务卡死（超过必释放，让其他副本接管，避免单点失效）；
 * {@code lockAtLeastFor=4s} 防止短任务在多数调度框架下被同一副本连续抢到导致其他副本饿死。
 * 二者均须 < 调度间隔（5s 仅约束 lockAtLeastFor；lockAtMostFor 仅作兜底上限）。</p>
 *
 * <p>本调度器依赖 {@code ShedLockConfig} 提供的 {@code @EnableSchedulerLock} 与 JDBC 锁提供器，
 * 而 ShedLockConfig 同样以 {@code redis-buffer-enabled=true} 为门控——两者天然同生共灭。</p>
 */
@Component
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.COUNTER_REDIS_BUFFER_ENABLED, havingValue = "true")
public class ClusteredCounterFlushScheduler {

    private final CounterFlushService flushService;

    public ClusteredCounterFlushScheduler(CounterFlushService flushService) {
        this.flushService = flushService;
    }

    @Scheduled(fixedDelayString = "${turbofeed.counter.flush-interval-ms:5000}")
    @SchedulerLock(name = "tf-counter-flush", lockAtMostFor = "9s", lockAtLeastFor = "4s")
    public void flush() {
        flushService.flush();
    }
}
