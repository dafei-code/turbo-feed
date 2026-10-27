package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 靶心：死信告警的指标化（{@link DefaultDeadLetterAlert}）。
 *
 * <p>改造前只有日志，判死只能靠人 grep；验证重点不是「日志打了」，而是
 * <b>每个事件类型各计一次、且标签能区分</b>——下架类判死的告警严重度要能单独配规则。</p>
 */
class DefaultDeadLetterAlertTest {

    private SimpleMeterRegistry registry;
    private DefaultDeadLetterAlert alert;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        alert = new DefaultDeadLetterAlert(registry);
    }

    private static OutboxEventRepository.OutboxEvent event(String type) {
        return new OutboxEventRepository.OutboxEvent(1L, type, "agg-1", null, "{}",
                OutboxEventRepository.STATUS_DEAD, 5, 5, Instant.now());
    }

    private double count(String type) {
        return registry.get(DefaultDeadLetterAlert.METRIC_NAME).tag("type", type).counter().count();
    }

    @Test
    void onDead_incrementsCounterPerEventType() {
        alert.onDead(event(OutboxEventType.TIMELINE_REMOVE.name()), new RuntimeException("down"));
        alert.onDead(event(OutboxEventType.TIMELINE_REMOVE.name()), new RuntimeException("down"));
        alert.onDead(event(OutboxEventType.TIMELINE_APPEND.name()), new RuntimeException("down"));

        // 按类型分开计数：合并成一个总数就看不出「下架的告警更急」。
        assertEquals(2.0, count(OutboxEventType.TIMELINE_REMOVE.name()), 1e-9);
        assertEquals(1.0, count(OutboxEventType.TIMELINE_APPEND.name()), 1e-9);
    }

    @Test
    void onDead_doesNotThrowWhenCauseIsNull() {
        // 判死路径本身已在故障中，告警实现抛异常会把「处理失败」升级成「中继崩溃」。
        assertDoesNotThrow(() -> alert.onDead(event(OutboxEventType.TIMELINE_APPEND.name()), null));
        assertEquals(1.0, count(OutboxEventType.TIMELINE_APPEND.name()), 1e-9);
    }

    @Test
    void onDead_unknownTypeStillCounted() {
        // 未来新增事件类型忘了登记时，计数器按原值打标签而不是 NPE——
        // 宁可多一条意料之外的时序，也不能让告警在故障路径上把异常抛出去。
        assertDoesNotThrow(() -> alert.onDead(event("SOME_NEW_TYPE"), new RuntimeException("x")));
        assertEquals(1.0, count("SOME_NEW_TYPE"), 1e-9);
    }
}
