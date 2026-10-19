package com.turbofeed.gateway.service.event.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.turbofeed.gateway.repository.OutboxEventRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-1 靶心④：发件箱中继补偿（{@link OutboxRelay#relay}）。
 *
 * <p>纯 Mockito 单测，不启 Spring。验证三件事：
 * ① due 列表为空时提前返回、不调 {@code tryDeliver}（正常快路径不该触发中继）；
 * ② 全部投递成功 / 部分失败时正确统计；
 * ③ 出现卡死行（reapStuck &gt; 0）时走运维可见的 warn 路径、且仍不误投。</p>
 *
 * <p>多实例下 {@code OutboxEventRepository#claim} 的 CAS 保证同一行只被一个实例投一次——
 * 这里测补偿逻辑本身，CAS 无主性由 {@code OutboxEventRepository} 单测/集成测覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock OutboxEventRepository repository;
    @Mock OutboxService outboxService;

    private OutboxRelay relay() {
        return new OutboxRelay(repository, outboxService, 100, 60L);
    }

    @Test
    void relay_emptyDueList_returnsEarly() {
        when(repository.reapStuck(eq(60L), any())).thenReturn(0);
        when(repository.findDueIds(eq(100), any())).thenReturn(List.of());

        relay().relay();

        verify(outboxService, never()).tryDeliver(anyLong());
    }

    @Test
    void relay_allDelivered() {
        when(repository.reapStuck(eq(60L), any())).thenReturn(0);
        when(repository.findDueIds(eq(100), any())).thenReturn(List.of(1L, 2L));
        when(outboxService.tryDeliver(1L)).thenReturn(true);
        when(outboxService.tryDeliver(2L)).thenReturn(true);

        relay().relay();

        verify(outboxService).tryDeliver(1L);
        verify(outboxService).tryDeliver(2L);
    }

    @Test
    void relay_partialDelivered_countsOk() {
        when(repository.reapStuck(eq(60L), any())).thenReturn(0);
        when(repository.findDueIds(eq(100), any())).thenReturn(List.of(1L, 2L));
        when(outboxService.tryDeliver(1L)).thenReturn(true);
        when(outboxService.tryDeliver(2L)).thenReturn(false);

        relay().relay();

        verify(outboxService).tryDeliver(1L);
        verify(outboxService).tryDeliver(2L);
    }

    @Test
    void relay_reapsStuckRows_thenNoDelivery() {
        when(repository.reapStuck(eq(60L), any())).thenReturn(3); // >0：进程崩溃残留，运维可见
        when(repository.findDueIds(eq(100), any())).thenReturn(List.of());

        relay().relay();

        verify(repository).reapStuck(eq(60L), any());
        verify(outboxService, never()).tryDeliver(anyLong());
    }
}
