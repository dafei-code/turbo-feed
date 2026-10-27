package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * M4 可观测性靶心：发件箱积压水位（{@link OutboxBacklogMetrics}）。
 *
 * <p>用 {@link SimpleMeterRegistry} 而非 Mockito 打桩 {@code MeterRegistry}：这里要验证的正是
 * <b>指标真的注册上去了、读数真的变了</b>，把 registry 换成 mock 就等于只验证「调了方法」，
 * 而 Micrometer 的坑（弱引用被 GC、Gauge 停在上一次的值）全在真实 registry 的读路径上。</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxBacklogMetricsTest {

    @Mock OutboxEventRepository repository;

    private SimpleMeterRegistry registry;
    private OutboxBacklogMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OutboxBacklogMetrics(repository, registry);
    }

    /** 读数辅助：Gauge 名 + 状态标签。 */
    private double gauge(String name, String status) {
        Gauge g = registry.find(name).tag("status", status).gauge();
        assertNotNull(g, "指标未注册: " + name + "{status=" + status + "}");
        return g.value();
    }

    @Test
    void constructor_registersAllFourStatusesAsZero() {
        // 四个非终态 × 两个指标，即使一条数据都没有也必须全部存在且为 0。
        // 少了它们，Prometheus 侧「积压归零」会表现为「时序消失」，与其语义完全不同。
        for (String status : List.of("PENDING", "SENDING", "FAILED", "DEAD")) {
            assertEquals(0.0, gauge("tf_outbox_backlog", status), 1e-9, "初始积压应为 0: " + status);
            assertEquals(0.0, gauge("tf_outbox_oldest_age_seconds", status), 1e-9, "初始年龄应为 0: " + status);
        }
        // SENT 是终态，不应被跟踪。
        assertNull(registry.find("tf_outbox_backlog").tag("status", "SENT").gauge());
    }

    @Test
    void refresh_publishesCountsAndOldestAge() {
        Instant now = Instant.now();
        when(repository.backlog()).thenReturn(List.of(
                new OutboxEventRepository.BacklogRow("PENDING", 7, now.minus(Duration.ofSeconds(120))),
                new OutboxEventRepository.BacklogRow("DEAD", 3, now.minus(Duration.ofSeconds(3600)))));

        metrics.refresh();

        assertEquals(7.0, gauge("tf_outbox_backlog", "PENDING"), 1e-9);
        assertEquals(3.0, gauge("tf_outbox_backlog", "DEAD"), 1e-9);
        // 年龄允许 ±2s 抖动（refresh 内部另取了一次 Instant.now()）。
        assertEquals(120.0, gauge("tf_outbox_oldest_age_seconds", "PENDING"), 2.0);
        assertEquals(3600.0, gauge("tf_outbox_oldest_age_seconds", "DEAD"), 2.0);
        // 没查到的状态保持 0，而不是沿用别的状态的值。
        assertEquals(0.0, gauge("tf_outbox_backlog", "FAILED"), 1e-9);
    }

    @Test
    void refresh_backlogDrained_resetsToZero() {
        // 第二轮查询为空（死信已消化）→ 必须回到 0。
        // 若只「用查到覆盖」，Gauge 会永远停在 37 上，制造「消化完了还在告警」的假象。
        when(repository.backlog())
                .thenReturn(List.of(new OutboxEventRepository.BacklogRow("DEAD", 37, Instant.now())))
                .thenReturn(List.of());

        metrics.refresh();
        assertEquals(37.0, gauge("tf_outbox_backlog", "DEAD"), 1e-9);

        metrics.refresh();
        assertEquals(0.0, gauge("tf_outbox_backlog", "DEAD"), 1e-9);
        assertEquals(0.0, gauge("tf_outbox_oldest_age_seconds", "DEAD"), 1e-9);
    }

    @Test
    void refresh_queryFails_keepsPreviousValueAndDoesNotThrow() {
        // 查失败时保留上一轮的值：指标停在旧值比掉到 0 更接近事实（掉 0 = 「积压已消化」的误导）。
        when(repository.backlog())
                .thenReturn(List.of(new OutboxEventRepository.BacklogRow("FAILED", 5, Instant.now())))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("db down"));

        metrics.refresh();
        assertEquals(5.0, gauge("tf_outbox_backlog", "FAILED"), 1e-9);

        assertDoesNotThrow(metrics::refresh, "统计失败不应把异常抛到调度线程外");
        assertEquals(5.0, gauge("tf_outbox_backlog", "FAILED"), 1e-9, "失败后应保留上一轮值");
    }

    @Test
    void refresh_unknownStatus_skippedWithoutThrowing() {
        // 库里出现未登记的状态（手写 SQL / 未来新增状态）不应炸掉整个刷新，该状态其余值照常更新。
        when(repository.backlog()).thenReturn(List.of(
                new OutboxEventRepository.BacklogRow("ARCHIVED", 9, Instant.now()),
                new OutboxEventRepository.BacklogRow("PENDING", 2, null)));

        assertDoesNotThrow(metrics::refresh);
        assertEquals(2.0, gauge("tf_outbox_backlog", "PENDING"), 1e-9);
        // oldest 为 null（理论上不该出现）时年龄按 0 处理，不 NPE。
        assertEquals(0.0, gauge("tf_outbox_oldest_age_seconds", "PENDING"), 1e-9);
    }
}
