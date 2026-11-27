package com.turbofeed.gateway.service.event.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * P2 CDC 消费者单测：
 * ① 正常事件调 OutboxService.tryDeliver（复用 claim CAS 幂等，不另写投递逻辑）；
 * ② 坏消息上抛（触发 RocketMQ 重试/DLQ，不静默吞）；
 * ③ tryDeliver 抛异常时包装上抛，交给 MQ 重试。
 */
@ExtendWith(MockitoExtension.class)
class OutboxCdcConsumerTest {

    @Mock OutboxService outboxService;

    @Test
    void validEvent_delegatesToTryDeliver() {
        when(outboxService.tryDeliver(123L)).thenReturn(true);
        OutboxCdcConsumer consumer = new OutboxCdcConsumer(outboxService);

        assertDoesNotThrow(() -> consumer.onMessage(new OutboxCdcEvent(123L, "trace-1")));

        verify(outboxService).tryDeliver(123L);
    }

    @Test
    void nullOrInvalidEvent_throwsForMqRetry() {
        OutboxCdcConsumer consumer = new OutboxCdcConsumer(outboxService);

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(null));
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(new OutboxCdcEvent(0L, null)));
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(new OutboxCdcEvent(-1L, null)));

        verifyNoInteractions(outboxService);
    }

    @Test
    void tryDeliverThrows_wrappedForMqRetry() {
        when(outboxService.tryDeliver(456L)).thenThrow(new RuntimeException("db down"));
        OutboxCdcConsumer consumer = new OutboxCdcConsumer(outboxService);

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(new OutboxCdcEvent(456L, null)));
    }
}
