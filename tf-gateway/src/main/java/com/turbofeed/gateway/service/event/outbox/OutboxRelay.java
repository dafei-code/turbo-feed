package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import com.turbofeed.gateway.trace.TraceContext;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 发件箱中继：把库里「待投递 / 失败待重试 / 卡死待回收」的事件投出去（P0-3 的补偿通道）。
 *
 * <p><b>它只在异常情况下才真正干活</b>：正常请求由 {@link OutboxService#deliverAfterCommit}
 * 在事务提交后直投完成，中继轮询到的应该永远是空列表。这不是浪费——轮询是「保险费」，
 * 保费极低（一次带索引的小查询），赔付的是「内容已可见却没进时间线」这种难排查的静默故障。</p>
 *
 * <h3>多实例与并发</h3>
 * <p>多实例部署时每个实例都会轮询，靠 {@link OutboxEventRepository#claim} 的 CAS
 * （PENDING/FAILED → SENDING）保证同一行只被一个实例投一次；其余实例 CAS 落空直接跳过。
 * 这里刻意<b>不做</b>选主：选主会引入「主挂了谁接管」的新问题，而 CAS 天然无主、无协调成本。</p>
 *
 * <p><b>可关</b>：{@code turbofeed.outbox.relay-enabled=false}。关掉不影响事件落库与快路径直投，
 * 只是失去补偿——仅在排障（例如怀疑中继刷爆下游）时临时使用。</p>
 *
 * <h3>PR-D4：轮次耗时指标</h3>
 * <p>改造前每轮只有一行 {@code due=X, 成功=Y, 失败=Z} 日志，且<b>空轮不打日志</b>——
 * 「Relay 调度线程还活着但不再产出」与「正常空转」在监控上完全无法区分。
 * 现补 {@code tf_outbox_relay_round_duration_seconds}（Timer）：它自带 {@code count}，
 * 因此既能量出一轮耗时（持续 &gt; {@code poll-interval-ms} 说明批量/消费能力不足），
 * 又能用 {@code rate(count)} 直接判定「调度假死」（count 停止增长 = 线程不再产出）。
 * 这与 {@code tf_outbox_oldest_age_seconds{status}} 是两个互补信号：
 * 前者回答「补偿线程还在跑吗」，后者回答「还有多少行没被补偿」。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** PR-D4 指标名（与发件箱既有 tf_outbox_* 指标族命名一致）。 */
    static final String METRIC_ROUND_DURATION = "tf_outbox_relay_round_duration_seconds";

    private final OutboxEventRepository repository;
    private final OutboxService outboxService;
    private final int batchSize;
    private final long stuckTimeoutSeconds;

    /**
     * 可选字段注入而非构造器参数：本类已被既有单测用四参构造器直接 {@code new}，
     * 改签名会破坏测试契约；MeterRegistry 缺失（裁剪部署 / 单测）时整体跳过计时，零回归。
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public OutboxRelay(OutboxEventRepository repository,
                       OutboxService outboxService,
                       @Value("${turbofeed.outbox.batch-size:100}") int batchSize,
                       @Value("${turbofeed.outbox.stuck-timeout-seconds:60}") long stuckTimeoutSeconds) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.batchSize = batchSize;
        this.stuckTimeoutSeconds = stuckTimeoutSeconds;
    }

    /**
     * 一轮补偿：先回收卡死行，再投递到期行。
     *
     * <p>{@code fixedDelay}（而非 {@code fixedRate}）：上一轮没跑完就不会开下一轮，
     * 避免慢下游时中继自身堆积成雪崩。</p>
     */
    @Scheduled(initialDelayString = "${turbofeed.outbox.initial-delay-ms:10000}",
               fixedDelayString = "${turbofeed.outbox.poll-interval-ms:2000}")
    public void relay() {
        Map<String, String> previous = TraceContext.beginCycle();
        long startedAt = System.nanoTime();
        try {
            relayInternal();
        } finally {
            // 计时必须放在 finally：即使中途抛异常，这一轮的耗时与轮次数也要留下，
            // 否则「每轮都失败」会表现为指标完全不增长，反而更像健康空转。
            recordRoundDuration(startedAt);
            TraceContext.end(previous);
        }
    }

    /** 记录一轮耗时；MeterRegistry 缺失时静默跳过（不因观测影响补偿链路）。 */
    private void recordRoundDuration(long startedAt) {
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }
        try {
            Timer.builder(METRIC_ROUND_DURATION)
                    .description("发件箱补偿单轮耗时（count 即轮次数，可用于判定调度假死）")
                    .register(registry)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            log.warn("发件箱轮次耗时指标记录失败（忽略，不影响补偿）: {}", e.getMessage());
        }
    }

    /**
     * 实际补偿逻辑：拆成独立方法，让 {@link #relay()} 用 {@code try/finally} 完整包住
     * 本轮所有日志（含中途 return 与异常），且不必改动原逻辑的缩进。
     * 多副本并发轮询时，同一轮的「回收卡死行」「补偿统计」靠 cycleId 聚合、靠 instanceId 区分实例。
     */
    private void relayInternal() {
        Instant now = Instant.now();
        int reaped = repository.reapStuck(stuckTimeoutSeconds, now);
        if (reaped > 0) {
            // 出现即意味着「有实例在投递途中崩溃过」——正常不该出现，值得运维看见。
            log.warn("发件箱回收卡死投递 {} 行（进程崩溃残留，SENDING 超时 {}s → FAILED）", reaped, stuckTimeoutSeconds);
        }
        List<Long> ids = repository.findDueIds(batchSize, now);
        if (ids.isEmpty()) {
            return;
        }
        int ok = 0;
        for (long id : ids) {
            if (outboxService.tryDeliver(id)) {
                ok++;
            }
        }
        log.info("发件箱补偿一轮: due={}, 成功={}, 失败={}", ids.size(), ok, ids.size() - ok);
    }
}
