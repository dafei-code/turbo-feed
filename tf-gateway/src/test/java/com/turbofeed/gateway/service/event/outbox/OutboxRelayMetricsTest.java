package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PR-D4：Relay 轮次耗时指标。
 *
 * <p><b>为什么这个指标不能靠日志代替</b>：Relay 空轮不打日志，所以「调度线程活着但不再产出」
 * 与「正常空转」在日志上完全一样。Timer 自带 {@code count}，即使一轮耗时接近 0，
 * 轮次数仍会稳定增长——{@code rate(count)} 掉到 0 才是「Relay 假死」的可靠信号。</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayMetricsTest {

    @Mock OutboxEventRepository repository;
    @Mock OutboxService outboxService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private OutboxRelay relay(boolean withRegistry) {
        OutboxRelay r = new OutboxRelay(repository, outboxService, 100, 60L);
        if (withRegistry) {
            ReflectionTestUtils.setField(r, "meterRegistry", registry);
        }
        return r;
    }

    private Timer timer() {
        Timer t = registry.find(OutboxRelay.METRIC_ROUND_DURATION).timer();
        assertNotNull(t, "轮次耗时 Timer 未注册");
        return t;
    }

    @Test
    void emptyRound_stillRecordsDurationAndCount() {
        when(repository.reapStuck(eq(60L), any())).thenReturn(0);
        when(repository.findDueIds(eq(100), any())).thenReturn(List.of());

        relay(true).relay();

        assertEquals(1L, timer().count(), "空轮也必须计数，否则无法判定 Relay 假死");
        assertTrue(timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) >= 0);
    }

    @Test
    void multipleRounds_countAccumulates() {
        when(repository.reapStuck(eq(60L), any())).thenReturn(0);
        when(repository.findDueIds(eq(100), any())).thenReturn(List.of(1L));

        OutboxRelay r = relay(true);
        r.relay();
        r.relay();
        r.relay();

        assertEquals(3L, timer().count());
    }

    @Test
    void failedRound_stillRecordsDuration_soFailureDoesNotLookLikeIdle() {
        // 一轮抛异常时也必须留下耗时与轮次数，否则「每轮都失败」会伪装成健康空转。
        // relay() 的 finally 只做计时与 MDC 清理，异常按原语义继续上抛给 Spring 调度器。
        when(repository.reapStuck(eq(60L), any())).thenThrow(new IllegalStateException("db down"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> relay(true).relay());

        assertEquals(1L, timer().count(), "异常轮也必须计数，否则失败会表现为无数据");
    }

    @Test
    void meterRegistryAbsent_noMetricNoRegression() {
        when(repository.reapStuck(eq(60L), any())).thenReturn(0);
        when(repository.findDueIds(eq(100), any())).thenReturn(List.of());

        assertDoesNotThrow(() -> relay(false).relay());
        assertTrue(registry.find(OutboxRelay.METRIC_ROUND_DURATION).timer() == null
                || registry.find(OutboxRelay.METRIC_ROUND_DURATION).timer().count() == 0);
    }
}
