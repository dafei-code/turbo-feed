package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import com.turbofeed.gateway.trace.TraceContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 发件箱终态行清理器（P3 分片前置：先清 SENT，再分片）。
 *
 * <h3>它补的是哪个洞</h3>
 * <p>{@code outbox_event} 的 {@code SENT} 是终态、只增不减，是表体积的绝对主体。
 * 原设计的单表 + 顺序消费在中小数据量下没问题，但一旦分片，扫描类查询
 * （{@code findDueIds}/{@code reapStuck}/{@code findDeadIds}/{@code backlog}）会广播到所有分片，
 * 广播代价会抵消写入分散的收益。<b>先把没有价值的终态行清掉</b>，让活跃表常年只留极少
 * 非终态行，分片才是净收益。</p>
 *
 * <h3>安全性</h3>
 * <ul>
 *   <li><b>只删 SENT</b>：非终态（PENDING/SENDING/FAILED/DEAD）一行不碰——那是待投递的承诺，
 *       删了就是真丢消息。DEAD 即使达到重投上限也保留供人工对账。</li>
 *   <li><b>保留窗口</b>：只删 {@code created_at < now - retention-days} 的冷行，给对账/审计留缓冲。</li>
 *   <li><b>分批</b>：每轮每片最多 {@code batchSize} 条，{@code fixedDelay} 多轮收敛，避免长事务。</li>
 *   <li><b>fail-open</b>：清理失败只 WARN + 计数，绝不抛出影响其他调度任务。</li>
 * </ul>
 *
 * <p><b>可关</b>：{@code turbofeed.outbox.sent-cleaner-enabled=false}。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.outbox.sent-cleaner-enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxSentCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutboxSentCleaner.class);

    /** 清理成功累计行数（可观测：确认真在收敛，而非任务假死）。 */
    static final String METRIC_PURGED = "tf_outbox_sent_purged_total";

    private final OutboxEventRepository repository;
    private final int batchSize;
    private final long retentionDays;

    /** 可选注入：裁剪部署 / 单测缺失时跳过指标，不影响清理本身。 */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public OutboxSentCleaner(OutboxEventRepository repository,
                             @Value("${turbofeed.outbox.sent-cleaner.batch-size:500}") int batchSize,
                             @Value("${turbofeed.outbox.sent-cleaner.retention-days:7}") long retentionDays) {
        this.repository = repository;
        this.batchSize = Math.max(1, batchSize);
        this.retentionDays = Math.max(1, retentionDays);
    }

    /**
     * 一轮清理。{@code fixedDelay}：DB 慢时不堆叠并发删除。
     * 默认每小时一轮、启动后 5 分钟才开始（避开启动期元数据加载与首轮投递）。
     */
    @Scheduled(initialDelayString = "${turbofeed.outbox.sent-cleaner.initial-delay-ms:300000}",
               fixedDelayString = "${turbofeed.outbox.sent-cleaner.poll-interval-ms:3600000}")
    public void clean() {
        Map<String, String> previous = TraceContext.beginCycle();
        try {
            cleanInternal();
        } finally {
            TraceContext.end(previous);
        }
    }

    /** 实际清理逻辑：拆出独立方法，由 {@link #clean()} 包裹轮次 MDC 后调用。 */
    void cleanInternal() {
        Instant before = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try {
            int deleted = repository.purgeSent(batchSize, before);
            if (deleted > 0) {
                log.info("发件箱终态清理一轮: 删除 SENT {} 行（保留 {} 天内）", deleted, retentionDays);
                recordPurged(deleted);
            }
        } catch (Exception e) {
            // fail-open：清理是纯运维行为，失败不能影响投递链路与其他调度任务。
            log.warn("发件箱终态清理失败（忽略，不影响投递）: {}", e.getMessage());
        }
    }

    private void recordPurged(int count) {
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }
        try {
            Counter.builder(METRIC_PURGED)
                    .description("发件箱已清理的 SENT 终态行数累计")
                    .register(registry)
                    .increment(count);
        } catch (Exception e) {
            log.warn("发件箱终态清理指标记录失败（忽略）: {}", e.getMessage());
        }
    }
}
