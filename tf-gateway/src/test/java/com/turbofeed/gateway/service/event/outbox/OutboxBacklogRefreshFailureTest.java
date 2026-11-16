package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * PR-D4：水位刷新失败必须留下可告警时序。
 *
 * <p>本类守护的是「指标本身可不可信」：查询失败时 Gauge 会<b>保留上一轮值</b>，
 * 这在 Grafana 上和「积压真的没变化」长得一模一样。计数器把这两种情况区分开，
 * 也是 {@link OutboxBacklogMetrics} 类注释里早就写明、但一直没实现的那个缺口。</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxBacklogRefreshFailureTest {

    @Mock OutboxEventRepository repository;

    private SimpleMeterRegistry registry;
    private OutboxBacklogMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OutboxBacklogMetrics(repository, registry);
    }

    private double failedCount() {
        Counter c = registry.find(OutboxBacklogMetrics.METRIC_REFRESH_FAILED).counter();
        return c == null ? 0.0 : c.count();
    }

    private double backlog(String status) {
        Gauge g = registry.find("tf_outbox_backlog").tag("status", status).gauge();
        assertNotNull(g);
        return g.value();
    }

    @Test
    void refreshFailure_incrementsCounter_andKeepsPreviousValue() {
        // 先让一轮成功，写入非零水位
        when(repository.backlog()).thenReturn(List.of(
                new OutboxEventRepository.BacklogRow("PENDING", 7L, Instant.now().minusSeconds(30))));
        metrics.refresh();
        assertEquals(7.0, backlog("PENDING"));
        assertEquals(0.0, failedCount());

        // 之后查询抛异常：值必须停在上一次，同时失败计数 +1
        when(repository.backlog()).thenThrow(new IllegalStateException("db down"));
        metrics.refresh();
        assertEquals(7.0, backlog("PENDING"), "失败时保留上一轮值，不能掉 0 伪装成积压消化完");
        assertEquals(1.0, failedCount());

        metrics.refresh();
        assertEquals(2.0, failedCount(), "每次失败都要计数，rate() 才能反映故障持续时长");
    }

    @Test
    void firstRefreshFails_counterDistinguishesZeroFromRealEmpty() {
        // 首次刷新就失败：Gauge 的 0 与「真的没积压」不可区分，只能靠计数器区分
        when(repository.backlog()).thenThrow(new IllegalStateException("db down"));

        metrics.refresh();

        assertEquals(0.0, backlog("PENDING"));
        assertEquals(1.0, failedCount(), "计数器非零即说明这个 0 不可信");
    }

    @Test
    void successAfterFailure_doesNotIncrementCounter_andRestoresFreshValue() {
        // 一次性的连续 stub：第二次 when() 会先执行上一次的 thenThrow，不能分两次写 when(...)
        when(repository.backlog())
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of());
        metrics.refresh();
        metrics.refresh();

        assertEquals(1.0, failedCount(), "成功轮不能计为失败");
        assertEquals(0.0, backlog("PENDING"), "恢复后应正确归零");
    }
}
