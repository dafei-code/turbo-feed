package com.turbofeed.counter.bucket;

import com.turbofeed.counter.cache.CounterReadService;
import com.turbofeed.counter.persist.PostCounterRepository;
import com.turbofeed.shared.model.CounterView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M4 可观测性靶心：计数 flush 周期的指标（{@link CounterFlushService}）。
 *
 * <p>验证的是「指标能不能回答运维真正会问的问题」，而不是「有没有调 registry」：</p>
 * <ul>
 *   <li>本周期到底落了多少帖？（{@code tf_counter_flush_batch_size}）</li>
 *   <li>调度还活着吗？（{@code tf_counter_flush_empty_total} —— 没有它，「无增量」与「调度挂了」在指标上同形）</li>
 *   <li>重放路径被走到了吗？（{@code tf_counter_flush_dedup_skipped_total} 应为 0）</li>
 *   <li>失败周期也留下耗时吗？（timer 在 finally 里记）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CounterFlushServiceTest {

    @Mock CounterBuffer buffer;
    @Mock PostCounterRepository repo;
    @Mock CounterReadService readService;
    @Mock JdbcTemplate jdbcTemplate;

    private SimpleMeterRegistry registry;
    private CounterFlushService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new CounterFlushService(buffer, repo, readService, jdbcTemplate, registry);
    }

    private double batchSampleCount() {
        return registry.get("tf_counter_flush_batch_size").summary().count();
    }

    private double timerCount() {
        return registry.get("tf_counter_flush_duration_seconds").timer().count();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void flush_withData_recordsBatchSizeAndRefresh() {
        Map<String, long[]> snapshot = Map.of("p1", new long[]{2, 1, 0, 0}, "p2", new long[]{0, 0, 3, 0});
        when(buffer.drain()).thenReturn(snapshot);
        when(repo.applyBatch(eq(snapshot), anyString()))
                .thenReturn(new PostCounterRepository.ApplyResult(2, 0));
        when(repo.getMany(anySet())).thenReturn(Map.of("p1", CounterView.zero("p1")));
        when(buffer.get(anyString())).thenReturn(null);

        service.flush();

        assertEquals(1.0, batchSampleCount(), 1e-9, "一个非空周期应产生一个 batch_size 样本");
        assertEquals(2.0, registry.get("tf_counter_flush_batch_size").summary().totalAmount(), 1e-9);
        assertEquals(1.0, timerCount(), 1e-9, "每次 flush 都应记一次耗时");
        assertEquals(0.0, counter("tf_counter_flush_empty_total"), 1e-9);
        assertEquals(0.0, counter("tf_counter_flush_dedup_skipped_total"), 1e-9);
        // L1/L2 修正确实执行（否则读路径会一直读旧值）。
        verify(readService, times(2)).refresh(anyString(), any());
    }

    @Test
    void flush_emptySnapshot_countsEmptyCycleAndSkipsWork() {
        when(buffer.drain()).thenReturn(Map.of());

        service.flush();

        assertEquals(1.0, counter("tf_counter_flush_empty_total"), 1e-9);
        assertEquals(0.0, batchSampleCount(), 1e-9, "空周期不该产生 batch_size 样本");
        assertEquals(1.0, timerCount(), 1e-9, "空周期也有耗时，仍应记一次");
        verify(repo, never()).applyBatch(anyMap(), anyString());
        verifyNoInteractions(readService);
    }

    @Test
    void flush_dedupSkipped_incrementsCounter() {
        Map<String, long[]> snapshot = Map.of("p1", new long[]{1, 0, 0, 0});
        when(buffer.drain()).thenReturn(snapshot);
        when(repo.applyBatch(eq(snapshot), anyString()))
                .thenReturn(new PostCounterRepository.ApplyResult(0, 1));
        when(repo.getMany(anySet())).thenReturn(Map.of());
        when(buffer.get(anyString())).thenReturn(null);

        service.flush();

        // 重放被 dedup 拦下：计数正确（0 条落库）但必须可见——否则 flush 反复失败重试没人知道。
        assertEquals(1.0, counter("tf_counter_flush_dedup_skipped_total"), 1e-9);
        assertEquals(1.0, batchSampleCount(), 1e-9);
    }

    @Test
    void flush_applyBatchThrows_stillRecordsDurationAndPropagates() {
        Map<String, long[]> snapshot = Map.of("p1", new long[]{1, 0, 0, 0});
        when(buffer.drain()).thenReturn(snapshot);
        when(repo.applyBatch(eq(snapshot), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        // 异常必须上抛：调度器下个周期用新 batch_id 重放，是「不丢增量」的唯一保障。
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, service::flush);
        // 失败周期同样留下耗时——慢在哪一步，失败时最需要知道。
        assertEquals(1.0, timerCount(), 1e-9);
        verifyNoInteractions(readService);
    }
}
