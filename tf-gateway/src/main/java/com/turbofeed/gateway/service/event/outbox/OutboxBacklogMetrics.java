package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发件箱积压水位指标（M4 可观测性／失败模式对账 §5.2 的缺口补位）。
 *
 * <h3>它补的是哪个洞</h3>
 * <p>改造前只有日志：{@code OutboxRelay} 每轮打印 {@code due=X, 成功=Y, 失败=Z}。
 * 日志能回答「这一轮投了几条」，回答不了「<b>现在积压多少、最老的一条卡了多久</b>」——
 * 而后者才是「补偿通道在正常空转」与「补偿通道已经追不上下游」的分水岭。
 * 没有这个数，发件箱积压到几万行也只能靠人翻日志。</p>
 *
 * <h3>指标</h3>
 * <ul>
 *   <li>{@code tf_outbox_backlog{status}} —— 各非终态的行数（Gauge）；</li>
 *   <li>{@code tf_outbox_oldest_age_seconds{status}} —— 各非终态最老一行的年龄（Gauge，秒）。</li>
 * </ul>
 * <p><b>四个状态在启动时就全部注册为 0</b>，而不是「查到哪个状态才建哪个」：
 * Prometheus 对不存在的时序会当成「无数据」，{@code rate()}/{@code absent()} 的语义都会变。
 * 积压归零时必须看到 0，而不是看它消失——「消失了」和「一直是 0」在告警规则里不是一回事。</p>
 *
 * <p>Gauge 持有 {@link AtomicLong} 的<b>强引用</b>（存在本类字段里）。Micrometer 的
 * {@code Gauge.builder(name, obj, f)} 对 {@code obj} 是弱引用，只把 AtomicLong 丢进局部变量
 * 注册 Gauge 会被 GC 掉、读数变成 0 或抛异常——这是 Micrometer 最经典的坑。</p>
 *
 * <h3>失败口径：fail-quiet，不 fail-open</h3>
 * <p>统计是纯粹的观测行为，DB 查不到不该影响发件箱投递（本类只在独立调度线程里跑，
 * 与投递链路无耦合）。但也不能静默：查失败时<b>保留上一轮的值</b>并告警日志，
 * 因为「指标停在旧值」比「指标掉到 0」更接近事实——掉 0 会让人误以为积压消化完了。
 * 首次刷新就失败时值为 0，与「真的没有积压」不可区分，这是本方案已知的局限
 * （唯一解法是加一个 {@code tf_outbox_backlog_refresh_failed_total} 计数器，见下）。</p>
 *
 * <p><b>可关</b>：{@code turbofeed.outbox.backlog-metrics-enabled=false}。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.outbox.backlog-metrics-enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMetrics.class);

    /**
     * 被跟踪的四个非终态，顺序固定（{@code SENT} 是终态、只增不减，统计它没有意义）。
     * 用 {@link LinkedHashMap} 保证注册顺序稳定，便于看指标时对照。
     */
    private static final List<String> TRACKED_STATUSES = List.of(
            OutboxEventRepository.STATUS_PENDING,
            OutboxEventRepository.STATUS_SENDING,
            OutboxEventRepository.STATUS_FAILED,
            OutboxEventRepository.STATUS_DEAD);

    private final OutboxEventRepository repository;
    private final Map<String, AtomicLong> backlog = new LinkedHashMap<>();
    private final Map<String, AtomicLong> oldestAgeSeconds = new LinkedHashMap<>();

    public OutboxBacklogMetrics(OutboxEventRepository repository, MeterRegistry registry) {
        this.repository = repository;
        for (String status : TRACKED_STATUSES) {
            // 必须先把 AtomicLong 存进字段（强引用），再注册 Gauge（弱引用）——见类注释。
            AtomicLong count = new AtomicLong(0);
            AtomicLong age = new AtomicLong(0);
            backlog.put(status, count);
            oldestAgeSeconds.put(status, age);
            Gauge.builder("tf_outbox_backlog", count, AtomicLong::get)
                    .description("发件箱各非终态积压行数")
                    .tag("status", status)
                    .register(registry);
            Gauge.builder("tf_outbox_oldest_age_seconds", age, AtomicLong::get)
                    .description("发件箱各非终态最老一行的年龄（秒）")
                    .tag("status", status)
                    .register(registry);
        }
    }

    /**
     * 刷新水位。{@code fixedDelay} 而非 {@code fixedRate}：DB 慢时不要堆叠无意义的并发查询。
     */
    @Scheduled(initialDelayString = "${turbofeed.outbox.backlog-initial-delay-ms:15000}",
               fixedDelayString = "${turbofeed.outbox.backlog-poll-ms:15000}")
    public void refresh() {
        List<OutboxEventRepository.BacklogRow> rows;
        try {
            rows = repository.backlog();
        } catch (Exception e) {
            // 保留上一轮的值（不置 0）：指标停在旧值比掉到 0 更接近事实。
            log.warn("发件箱积压统计失败，沿用上一轮水位: {}", e.getMessage());
            return;
        }
        Instant now = Instant.now();
        // 先把四个状态归零，再用查到覆盖：某状态下已经没有行时，Gauge 必须回到 0，
        // 否则会永远停在最后一次看到的非零值上（最坏情况：死信消化完了还在报 DEAD=37）。
        TRACKED_STATUSES.forEach(s -> {
            backlog.get(s).set(0);
            oldestAgeSeconds.get(s).set(0);
        });
        for (OutboxEventRepository.BacklogRow row : rows) {
            AtomicLong count = backlog.get(row.status());
            AtomicLong age = oldestAgeSeconds.get(row.status());
            if (count == null || age == null) {
                // 库里出现了本类不跟踪的状态值（如手写 SQL 写进了新状态）——不静默吞，留痕。
                log.warn("发件箱积压统计遇到未登记状态，已跳过: status={}, count={}", row.status(), row.count());
                continue;
            }
            count.set(row.count());
            age.set(row.oldest() == null ? 0 : Math.max(0, Duration.between(row.oldest(), now).toSeconds()));
        }
    }
}
