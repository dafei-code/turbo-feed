package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P3 分片前置：SENT 清理器单测（纯 Mockito，不启 Spring）。
 *
 * <p>验证：① 按保留窗口计算 before 并调用 purgeSent；② batchSize 下限钳制；
 * ③ 清理异常时 fail-open（不外抛）；④ 删除行数为 0 时不产生多余动作。</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxSentCleanerTest {

    @Mock OutboxEventRepository repository;

    @Test
    void clean_passesRetentionWindowAndBatchSize() {
        OutboxSentCleaner cleaner = new OutboxSentCleaner(repository, 500, 7);
        when(repository.purgeSent(eq(500), any(Instant.class))).thenReturn(120);

        cleaner.cleanInternal();

        verify(repository).purgeSent(eq(500), argThat(before ->
                before.isAfter(Instant.now().minusSeconds(8 * 86400)) // 约 7 天前
                        && before.isBefore(Instant.now().minusSeconds(6 * 86400))));
    }

    @Test
    void clean_clampsNonPositiveBatchSizeAndRetentionDays() {
        OutboxSentCleaner cleaner = new OutboxSentCleaner(repository, 0, 0);
        when(repository.purgeSent(anyInt(), any(Instant.class))).thenReturn(0);

        cleaner.cleanInternal();

        // batch-size 与 retention-days 都被钳到最小 1
        verify(repository).purgeSent(eq(1), argThat(before ->
                before.isBefore(Instant.now()) && before.isAfter(Instant.now().minusSeconds(3 * 86400))));
    }

    @Test
    void clean_repositoryThrows_failOpen() {
        OutboxSentCleaner cleaner = new OutboxSentCleaner(repository, 500, 7);
        when(repository.purgeSent(anyInt(), any(Instant.class)))
                .thenThrow(new RuntimeException("db down"));

        // 不抛出：清理是观测/运维行为，失败不影响投递链路
        cleaner.cleanInternal();

        verify(repository).purgeSent(anyInt(), any(Instant.class));
    }

    @Test
    void clean_zeroDeleted_noMetricNoNoise() {
        OutboxSentCleaner cleaner = new OutboxSentCleaner(repository, 500, 7);
        when(repository.purgeSent(anyInt(), any(Instant.class))).thenReturn(0);

        cleaner.cleanInternal(); // deleted=0 分支不记指标；meterRegistry 为 null 也不抛

        verify(repository).purgeSent(anyInt(), any(Instant.class));
    }
}
