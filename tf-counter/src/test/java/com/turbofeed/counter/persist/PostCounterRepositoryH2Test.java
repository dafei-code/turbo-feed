package com.turbofeed.counter.persist;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2（MySQL 模式）集成测试：不依赖 Redis / 网络，验证落库幂等与 ShedLock JDBC 可用性。
 *
 * <ul>
 *   <li>{@link #applyBatch_idempotent_sameBatchId_noDoubleCount}：同一 batch_id 重放 → 计数不变（幂等）。</li>
 *   <li>{@link #applyBatch_differentBatchId_bothApplied}：不同 batch_id 的同帖 → 各自合法落库（不误拦）。</li>
 *   <li>{@link #getMany_returnsAll}：批量回读映射正确，缺帖返回 null。</li>
 *   <li>{@link #shedLockJdbcProvider_worksOnH2}：ShedLock 的 JDBC 锁提供器在 H2 上可加锁/解锁。</li>
 * </ul>
 */
public class PostCounterRepositoryH2Test {

    private DataSource ds;
    private PostCounterRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        // 每个测试用独立命名的 H2 内存库（DB_CLOSE_DELAY=-1 保证本测试多次取连接共享同一库，
        // 但 UUID 命名避免跨测试相互污染，否则 in-memory 库会跨用例累积数据）。
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:tf-counter-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        ds = h2;
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("schema.sql")));
        }
        repo = new PostCounterRepository(new JdbcTemplate(ds));
    }

    @Test
    void applyBatch_idempotent_sameBatchId_noDoubleCount() {
        Map<String, long[]> snap = Map.of(
                "p1", new long[]{5, 0, 0},
                "p2", new long[]{0, 3, 1});
        repo.applyBatch(snap, "batch-1");
        // 重放同一 batch_id（模拟 ShedLock 锁过期续跑 / 进程崩溃重试）
        repo.applyBatch(snap, "batch-1");

        assertEquals(5L, repo.get("p1").likeCount());
        assertEquals(3L, repo.get("p2").commentCount());
        assertEquals(1L, repo.get("p2").shareCount());
    }

    @Test
    void applyBatch_differentBatchId_bothApplied() {
        repo.applyBatch(Map.of("p1", new long[]{2, 0, 0}), "b1");
        repo.applyBatch(Map.of("p1", new long[]{3, 0, 0}), "b2");

        assertEquals(5L, repo.get("p1").likeCount());
    }

    @Test
    void getMany_returnsAll() {
        repo.applyBatch(Map.of(
                "p1", new long[]{1, 1, 1},
                "p2", new long[]{2, 2, 2}), "b");
        Map<String, com.turbofeed.shared.model.CounterView> m =
                repo.getMany(Set.of("p1", "p2", "pX"));

        assertEquals(2, m.size());
        assertNotNull(m.get("p1"));
        assertNull(m.get("pX"));
    }

    @Test
    void shedLockJdbcProvider_worksOnH2() {
        JdbcLockProvider lp = new JdbcLockProvider(ds);
        Optional<SimpleLock> held = lp.lock(new LockConfiguration(
                Instant.now(), "tf-counter-flush", Duration.ofSeconds(9), Duration.ofSeconds(4)));
        assertTrue(held.isPresent(), "ShedLock JDBC 锁在 H2 上应可获取");
        held.get().unlock();
    }
}
