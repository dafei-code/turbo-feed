package com.turbofeed.counter.bucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-E 失败暂存 H2（MySQL 模式）集成测试：不依赖 Redis / 网络。
 *
 * <p>验证「整份快照 + 原 batchId」能被原样取回、重复登记不覆盖、重放成功后删除、
 * 以及 B4 dry-run 只读不改数据。schema.sql 会被真实执行，顺带守住 prod DDL 契约。</p>
 */
public class CounterFlushPendingStoreH2Test {

    private CounterFlushPendingStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:tf-counter-pending-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        DataSource ds = h2;
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("schema.sql")));
        }
        jdbc = new JdbcTemplate(ds);
        store = new CounterFlushPendingStore(jdbc, new ObjectMapper());
    }

    @Test
    void save_load_roundTripPreservesBatchIdAndAllDeltas() {
        Map<String, long[]> deltas = Map.of(
                "p1", new long[]{5, 1, 0, 2},
                "p2", new long[]{0, 0, 3, 0});
        store.save(new CounterFlushSnapshot("batch-A", deltas), "db down");

        List<CounterFlushPendingStore.PendingBatch> batches = store.loadOldest(10);
        assertEquals(1, batches.size());
        assertEquals("batch-A", batches.get(0).batchId());
        assertEquals("batch-A", batches.get(0).snapshot().batchId(), "重放必须复用原 batchId");
        assertArrayEquals(new long[]{5, 1, 0, 2}, batches.get(0).snapshot().deltas().get("p1"));
        assertArrayEquals(new long[]{0, 0, 3, 0}, batches.get(0).snapshot().deltas().get("p2"));
        assertEquals(1, batches.get(0).attempts());
        assertTrue(batches.get(0).lastError().contains("db down"));
    }

    @Test
    void save_sameBatchId_neverOverwritesOriginalSnapshot() {
        Map<String, long[]> original = Map.of("p1", new long[]{5, 1, 0, 2});
        Map<String, long[]> later = Map.of("pX", new long[]{99, 0, 0, 0});
        store.save(new CounterFlushSnapshot("batch-A", original), "first");
        store.save(new CounterFlushSnapshot("batch-A", later), "second");

        List<CounterFlushPendingStore.PendingBatch> batches = store.loadOldest(10);
        assertEquals(1, batches.size(), "同一批次只应有一行");
        assertEquals(2, batches.get(0).attempts(), "重复登记只累加次数");
        assertArrayEquals(new long[]{5, 1, 0, 2}, batches.get(0).snapshot().deltas().get("p1"));
        assertFalse(batches.get(0).snapshot().deltas().containsKey("pX"),
                "后来的数据绝不能覆盖原快照，否则重放会落错增量");
    }

    @Test
    void delete_removesBatch_afterSuccessfulReplay() {
        store.save(new CounterFlushSnapshot("batch-A", Map.of("p1", new long[]{1, 0, 0, 0})), "db down");
        assertEquals(1, store.loadOldest(10).size());

        store.delete("batch-A");

        assertTrue(store.loadOldest(10).isEmpty());
    }

    @Test
    void touch_incrementsAttempts_withoutTouchingPayload() {
        store.save(new CounterFlushSnapshot("batch-A", Map.of("p1", new long[]{1, 0, 0, 0})), "db down");
        store.touch("batch-A", "still down");

        CounterFlushPendingStore.PendingBatch b = store.loadOldest(10).get(0);
        assertEquals(2, b.attempts());
        assertTrue(b.lastError().contains("still down"));
        assertArrayEquals(new long[]{1, 0, 0, 0}, b.snapshot().deltas().get("p1"));
    }

    @Test
    void loadOldest_respectsLimit_andOrdersByCreatedAt() {
        store.save(new CounterFlushSnapshot("b1", Map.of("p1", new long[]{1, 0, 0, 0})), "x");
        store.save(new CounterFlushSnapshot("b2", Map.of("p2", new long[]{0, 1, 0, 0})), "x");
        store.save(new CounterFlushSnapshot("b3", Map.of("p3", new long[]{0, 0, 1, 0})), "x");

        List<CounterFlushPendingStore.PendingBatch> limited = store.loadOldest(2);
        assertEquals(2, limited.size());
        assertEquals(3, store.loadOldest(10).size());
    }

    @Test
    void dryRun_reportsPendingDeltas_andNeverMutatesData() {
        store.save(new CounterFlushSnapshot("b1", Map.of("p1", new long[]{5, 1, 0, 2})), "db down");
        store.save(new CounterFlushSnapshot("b2", Map.of("p2", new long[]{0, 0, 3, 0})), "db down");

        CounterFlushPendingStore.DryRunReport report = store.dryRun();

        assertEquals(2, report.pendingBatches());
        assertEquals(2, report.pendingPosts());
        assertArrayEquals(new long[]{5, 1, 3, 2}, report.pendingDeltas());
        assertEquals(2, report.batches().size());
        assertTrue(report.note().contains("dry-run"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_flush_pending", Integer.class),
                "dry-run 必须只读，不能删暂存行");
    }
}
