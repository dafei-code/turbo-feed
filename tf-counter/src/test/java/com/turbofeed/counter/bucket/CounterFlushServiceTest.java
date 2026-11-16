package com.turbofeed.counter.bucket;

import com.turbofeed.counter.cache.CounterReadService;
import com.turbofeed.counter.persist.PostCounterRepository;
import com.turbofeed.shared.model.CounterView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M4 可观测性 + PR-E 三段式靶心：计数 flush 周期的指标与失败交接。
 *
 * <ul>
 *   <li>本周期落了多少帖？调度还活着吗？重放路径被走到了吗？</li>
 *   <li><b>落库失败</b>：整份快照连同原 batchId 进失败暂存，不抛给调度器、不还回热缓冲。</li>
 *   <li><b>重放</b>：必须复用原 batchId（换 id 会让累加式 UPSERT 重复计数）。</li>
 *   <li><b>缓存修正失败</b>：数据已落库，不能把整批再存暂存造成重复累加。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CounterFlushServiceTest {

    @Mock CounterBuffer buffer;
    @Mock PostCounterRepository repo;
    @Mock CounterReadService readService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock CounterFlushPendingStore pendingStore;

    private SimpleMeterRegistry registry;
    private CounterFlushService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new CounterFlushService(buffer, repo, readService, jdbcTemplate, pendingStore, registry);
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

    private CounterFlushSnapshot snap(String batchId, Map<String, long[]> deltas) {
        return new CounterFlushSnapshot(batchId, deltas);
    }

    @Test
    void flush_withData_recordsBatchSizeAndRefresh() {
        Map<String, long[]> snapshot = Map.of("p1", new long[]{2, 1, 0, 0}, "p2", new long[]{0, 0, 3, 0});
        when(buffer.drain(anyString())).thenReturn(snap("b1", snapshot));
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
        verify(readService, times(2)).refresh(anyString(), any());
        verify(pendingStore, never()).save(any(), anyString());
    }

    @Test
    void flush_emptySnapshot_countsEmptyCycleAndSkipsWork() {
        when(buffer.drain(anyString())).thenReturn(CounterFlushSnapshot.empty("b-empty"));

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
        when(buffer.drain(anyString())).thenReturn(snap("b1", snapshot));
        when(repo.applyBatch(eq(snapshot), anyString()))
                .thenReturn(new PostCounterRepository.ApplyResult(0, 1));
        when(repo.getMany(anySet())).thenReturn(Map.of());
        when(buffer.get(anyString())).thenReturn(null);

        service.flush();

        assertEquals(1.0, counter("tf_counter_flush_dedup_skipped_total"), 1e-9);
        assertEquals(1.0, batchSampleCount(), 1e-9);
    }

    @Test
    void flush_applyBatchThrows_persistsSnapshotWithOriginalBatchId_andNeverRequeues() {
        Map<String, long[]> snapshot = Map.of("p1", new long[]{1, 0, 0, 0});
        when(buffer.drain(anyString())).thenReturn(snap("b-fail", snapshot));
        when(repo.applyBatch(eq(snapshot), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        // 异常不再上抛把快照丢掉：必须转成「整批暂存」
        assertDoesNotThrow(service::flush);

        ArgumentCaptor<CounterFlushSnapshot> cap = ArgumentCaptor.forClass(CounterFlushSnapshot.class);
        verify(pendingStore).save(cap.capture(), contains("db down"));
        assertEquals("b-fail", cap.getValue().batchId(), "重放必须复用原 batchId");
        assertSame(snapshot, cap.getValue().deltas(), "整份快照原样暂存，不能被替换或裁剪");
        assertEquals(1.0, counter("tf_counter_flush_failed_total"), 1e-9);
        assertEquals(1.0, counter("tf_counter_flush_pending_saved_total"), 1e-9);
        assertEquals(1.0, timerCount(), 1e-9, "失败周期同样留下耗时");
        verify(buffer, never()).add(anyString(), any(), anyLong());
        verifyNoInteractions(readService);
    }

    @Test
    void flush_pendingStoreWriteFails_stillLogsAndDoesNotThrow() {
        Map<String, long[]> snapshot = Map.of("p1", new long[]{1, 0, 0, 0});
        when(buffer.drain(anyString())).thenReturn(snap("b-fail2", snapshot));
        when(repo.applyBatch(eq(snapshot), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        doThrow(new DataAccessResourceFailureException("pending table unavailable"))
                .when(pendingStore).save(any(), anyString());

        assertDoesNotThrow(service::flush);
        assertEquals(1.0, counter("tf_counter_flush_failed_total"), 1e-9);
        assertEquals(0.0, counter("tf_counter_flush_pending_saved_total"), 1e-9);
    }

    @Test
    void flush_replaysPending_withOriginalBatchId_thenDeletes() {
        Map<String, long[]> replay = Map.of("p9", new long[]{7, 0, 0, 0});
        CounterFlushSnapshot replaySnap = snap("original-batch", replay);
        when(pendingStore.loadOldest(anyInt())).thenReturn(List.of(
                new CounterFlushPendingStore.PendingBatch("original-batch", replaySnap,
                        2, Instant.now(), "db down")));
        when(buffer.drain(anyString())).thenReturn(CounterFlushSnapshot.empty("new-batch"));
        when(repo.applyBatch(eq(replay), eq("original-batch")))
                .thenReturn(new PostCounterRepository.ApplyResult(1, 0));
        when(repo.getMany(anySet())).thenReturn(Map.of());
        when(buffer.get(anyString())).thenReturn(null);

        service.flush();

        verify(repo).applyBatch(eq(replay), eq("original-batch"));
        verify(pendingStore).delete("original-batch");
        assertEquals(1.0, counter("tf_counter_flush_replayed_total"), 1e-9);
        assertEquals(1.0, counter("tf_counter_flush_empty_total"), 1e-9, "本周期新增量为空");
    }

    @Test
    void flush_pendingReplayFails_keepsSnapshotAndTouchesAttempts() {
        Map<String, long[]> replay = Map.of("p9", new long[]{7, 0, 0, 0});
        when(pendingStore.loadOldest(anyInt())).thenReturn(List.of(
                new CounterFlushPendingStore.PendingBatch("stuck-batch", snap("stuck-batch", replay),
                        3, Instant.now(), "db down")));
        when(buffer.drain(anyString())).thenReturn(CounterFlushSnapshot.empty("new-batch"));
        when(repo.applyBatch(eq(replay), eq("stuck-batch")))
                .thenThrow(new DataAccessResourceFailureException("still down"));

        service.flush();

        verify(pendingStore).touch(eq("stuck-batch"), anyString());
        verify(pendingStore, never()).delete(anyString());
        assertEquals(0.0, counter("tf_counter_flush_replayed_total"), 1e-9);
    }

    @Test
    void flush_cacheRefreshFailure_doesNotPersistSnapshot() {
        // 数据已落库，L1/L2 修正失败不能再把整批存进暂存，否则重放会重复累加
        Map<String, long[]> snapshot = Map.of("p1", new long[]{1, 0, 0, 0});
        when(buffer.drain(anyString())).thenReturn(snap("b1", snapshot));
        when(repo.applyBatch(eq(snapshot), anyString()))
                .thenReturn(new PostCounterRepository.ApplyResult(1, 0));
        when(repo.getMany(anySet())).thenThrow(new DataAccessResourceFailureException("read fail"));

        assertDoesNotThrow(service::flush);

        verify(pendingStore, never()).save(any(), anyString());
        assertEquals(0.0, counter("tf_counter_flush_failed_total"), 1e-9);
    }
}
