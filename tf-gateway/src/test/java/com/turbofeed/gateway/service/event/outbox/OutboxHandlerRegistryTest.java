package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.repository.OutboxEventRepository;
import com.turbofeed.gateway.trace.TraceContext;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-C3：分发注册表的启动期穷尽校验，以及 OutboxService 改走注册表后的投递语义。
 *
 * <p>核心回归护栏：漏注册一个 Handler 必须<b>启动失败并点名类型</b>——
 * 旧的 switch 语句在这里是「编译通过、事件永远 PENDING」。</p>
 *
 * <p>本类的全量 Handler 列表按 {@link OutboxEventType#values()} 动态生成，
 * 这样以后新增事件类型时，注册表会先要求配套的 Handler，测试不会因手写列表过期而误报。</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxHandlerRegistryTest {

    @Mock private OutboxEventRepository repository;
    @Mock private SnowflakeIdGenerator idGenerator;
    @Mock private DeadLetterAlert deadLetterAlert;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 只声明类型、不做副作用的桩 Handler。 */
    private record StubHandler(OutboxEventType type) implements OutboxEventHandler {
        @Override
        public OutboxEventType type() {
            return type;
        }

        @Override
        public void handle(String payloadJson) {
            // no-op
        }
    }

    /** 给每个枚举值配一个桩，再用 overrides 覆盖指定类型（通常是 mock Handler）。 */
    private List<OutboxEventHandler> handlersWith(OutboxEventHandler... overrides) {
        Map<OutboxEventType, OutboxEventHandler> map = new EnumMap<>(OutboxEventType.class);
        for (OutboxEventType t : OutboxEventType.values()) {
            map.put(t, new StubHandler(t));
        }
        for (OutboxEventHandler h : overrides) {
            map.put(h.type(), h);
        }
        return List.copyOf(map.values());
    }

    @Test
    void registry_missingHandler_failsFastAndNamesType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OutboxHandlerRegistry(List.of(new StubHandler(OutboxEventType.TIMELINE_APPEND))));
        assertTrue(ex.getMessage().contains("TIMELINE_REMOVE"), "报错必须点名缺失类型，实际：" + ex.getMessage());
    }

    @Test
    void registry_duplicateHandler_failsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OutboxHandlerRegistry(List.of(
                        new StubHandler(OutboxEventType.TIMELINE_APPEND),
                        new StubHandler(OutboxEventType.TIMELINE_APPEND),
                        new StubHandler(OutboxEventType.TIMELINE_REMOVE))));
        assertTrue(ex.getMessage().contains("重复注册"), "实际：" + ex.getMessage());
    }

    @Test
    void registry_allTypesRegistered_passes() {
        OutboxHandlerRegistry registry = new OutboxHandlerRegistry(handlersWith());
        assertEquals(OutboxEventType.values().length, registry.registeredTypes().size());
        for (OutboxEventType t : OutboxEventType.values()) {
            assertTrue(registry.registeredTypes().contains(t));
        }
    }

    @Test
    void tryDeliver_dispatchesViaRegistry_andRestoresPayloadTraceId() throws Exception {
        OutboxEventHandler handler = org.mockito.Mockito.mock(OutboxEventHandler.class);
        when(handler.type()).thenReturn(OutboxEventType.TIMELINE_APPEND);

        String payload = "{\"item\":null,\"poolLevel\":1,\"traceId\":\"trace-xyz\"}";
        OutboxEventRepository.OutboxEvent row = new OutboxEventRepository.OutboxEvent(
                7L, OutboxEventType.TIMELINE_APPEND.name(), "media/1/x", 1L, payload,
                OutboxEventRepository.STATUS_PENDING, 0, 5, Instant.now());
        when(repository.findById(7L)).thenReturn(Optional.of(row));
        when(repository.claim(eq(7L), any())).thenReturn(true);

        // 在 Handler 内断言 MDC 已被恢复：中继重投不在原请求线程上，这一步丢了链路就查不到重投来源。
        org.mockito.Mockito.doAnswer(inv -> {
            assertEquals("trace-xyz", TraceContext.current());
            return null;
        }).when(handler).handle(payload);

        OutboxService service = service(handlersWith(handler));
        assertTrue(service.tryDeliver(7L));
        verify(handler).handle(payload);
        verify(repository).markSent(eq(7L), any());
        verify(repository, never()).markFailed(anyLong(), anyInt(), anyInt(), anyString(), anyLong(), any());
    }

    @Test
    void tryDeliver_legacyPayloadWithoutTraceId_stillDispatches() throws Exception {
        OutboxEventHandler handler = org.mockito.Mockito.mock(OutboxEventHandler.class);
        when(handler.type()).thenReturn(OutboxEventType.TIMELINE_REMOVE);

        String legacy = "{\"timelineKey\":\"post/1/x\"}";
        OutboxEventRepository.OutboxEvent row = new OutboxEventRepository.OutboxEvent(
                8L, OutboxEventType.TIMELINE_REMOVE.name(), "media/1/x", 1L, legacy,
                OutboxEventRepository.STATUS_PENDING, 0, 5, Instant.now());
        when(repository.findById(8L)).thenReturn(Optional.of(row));
        when(repository.claim(eq(8L), any())).thenReturn(true);

        assertTrue(service(handlersWith(handler)).tryDeliver(8L));
        verify(handler).handle(legacy);
    }

    @Test
    void tryDeliver_unknownTypeString_marksFailedNeverSilentlyDrops() {
        OutboxEventRepository.OutboxEvent row = new OutboxEventRepository.OutboxEvent(
                9L, "SOMETHING_NEW", "media/1/x", 1L, "{}",
                OutboxEventRepository.STATUS_PENDING, 0, 5, Instant.now());
        when(repository.findById(9L)).thenReturn(Optional.of(row));
        when(repository.claim(eq(9L), any())).thenReturn(true);
        when(repository.markFailed(eq(9L), anyInt(), anyInt(), anyString(), anyLong(), any())).thenReturn(false);

        assertFalse(service(handlersWith()).tryDeliver(9L));
        verify(repository).markFailed(eq(9L), anyInt(), anyInt(), anyString(), anyLong(), any());
        verify(repository, never()).markSent(anyLong(), any());
    }

    @Test
    void tryDeliver_dead_incrementsTypedCounter() throws Exception {
        OutboxEventHandler handler = org.mockito.Mockito.mock(OutboxEventHandler.class);
        when(handler.type()).thenReturn(OutboxEventType.TIMELINE_REMOVE);
        // 必须让 Handler 真的失败，才会走到 markFailed → 判死 → 计数这条分支。
        org.mockito.Mockito.doThrow(new java.io.IOException("downstream down"))
                .when(handler).handle(anyString());

        OutboxEventRepository.OutboxEvent row = new OutboxEventRepository.OutboxEvent(
                10L, OutboxEventType.TIMELINE_REMOVE.name(), "media/1/x", 1L, "{}",
                OutboxEventRepository.STATUS_PENDING, 5, 5, Instant.now());
        when(repository.findById(10L)).thenReturn(Optional.of(row));
        when(repository.claim(eq(10L), any())).thenReturn(true);
        when(repository.markFailed(eq(10L), anyInt(), anyInt(), anyString(), anyLong(), any())).thenReturn(true);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        assertFalse(service(handlersWith(handler), metrics).tryDeliver(10L));
        verify(deadLetterAlert).onDead(eq(row), any());
        assertEquals(1.0, metrics.get("tf_outbox_dead_total").tag("type", "TIMELINE_REMOVE").counter().count());
    }

    private OutboxService service(List<OutboxEventHandler> handlers) {
        return service(new OutboxHandlerRegistry(handlers), new SimpleMeterRegistry());
    }

    private OutboxService service(List<OutboxEventHandler> handlers, SimpleMeterRegistry metrics) {
        return service(new OutboxHandlerRegistry(handlers), metrics);
    }

    private OutboxService service(OutboxHandlerRegistry registry, SimpleMeterRegistry metrics) {
        return new OutboxService(repository, registry, mapper, idGenerator, deadLetterAlert, metrics, 5, 5);
    }
}
