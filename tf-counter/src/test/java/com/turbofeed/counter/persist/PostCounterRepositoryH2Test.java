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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2（MySQL 模式）集成测试：不依赖 Redis / 网络，验证落库幂等与 ShedLock JDBC 可用性。
 *
 * <ul>
 *   <li>{@link #applyBatch_idempotent_sameBatchId_noDoubleCount}：同一 batch_id 重放 → 计数不变（幂等）。</li>
 *   <li>{@link #applyBatch_differentBatchId_bothApplied}：不同 batch_id 的同帖 → 各自合法落库（不误拦）。</li>
 *   <li>{@link #getMany_returnsAll}：批量回读映射正确，缺帖返回 null。</li>
 *   <li>{@link #shedLockJdbcProvider_worksOnH2}：ShedLock 的 JDBC 锁提供器在 H2 上可加锁/解锁。</li>
 *   <li><b>M2 / 4.2 事务闭合</b>（{@link #applyBatch_upsertFails_dedupRolledBackAndReplaySucceeds}）：
 *       UPSERT 失败时去重登记必须一并回滚，否则该批增量会被「已登记」永久挡在门外 → 永久丢失。</li>
 * </ul>
 */
public class PostCounterRepositoryH2Test {

    private DataSource ds;
    private PostCounterRepository repo;
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate = new JdbcTemplate(ds);
        repo = new PostCounterRepository(jdbcTemplate, new DataSourceTransactionManager(ds));
    }

    @Test
    void applyBatch_idempotent_sameBatchId_noDoubleCount() {
        Map<String, long[]> snap = Map.of(
                "p1", new long[]{5, 0, 0, 0},
                "p2", new long[]{0, 3, 1, 0});
        repo.applyBatch(snap, "batch-1");
        // 重放同一 batch_id（模拟 ShedLock 锁过期续跑 / 进程崩溃重试）
        repo.applyBatch(snap, "batch-1");

        assertEquals(5L, repo.get("p1").likeCount());
        assertEquals(3L, repo.get("p2").commentCount());
        assertEquals(1L, repo.get("p2").shareCount());
        assertEquals(0L, repo.get("p2").commentLikeCount());
    }

    @Test
    void applyBatch_differentBatchId_bothApplied() {
        repo.applyBatch(Map.of("p1", new long[]{2, 0, 0, 0}), "b1");
        repo.applyBatch(Map.of("p1", new long[]{3, 0, 0, 0}), "b2");

        assertEquals(5L, repo.get("p1").likeCount());
    }

    @Test
    void getMany_returnsAll() {
        repo.applyBatch(Map.of(
                "p1", new long[]{1, 1, 1, 1},
                "p2", new long[]{2, 2, 2, 2}), "b");
        Map<String, com.turbofeed.shared.model.CounterView> m =
                repo.getMany(Set.of("p1", "p2", "pX"));

        assertEquals(2, m.size());
        assertNotNull(m.get("p1"));
        assertNull(m.get("pX"));
        assertEquals(1L, m.get("p1").commentLikeCount());
    }

    // ==================== M2 / 4.2 事务闭合 ====================

    /**
     * UPSERT 失败时，去重登记必须一并回滚（否则该批增量永久丢失）。
     *
     * <p><b>改造前的失效路径</b>：登记与 UPSERT 是两个独立的自动提交，登记成功、UPSERT 失败后，
     * dedup 表里留下了本批记录；下次重放（哪怕是另一个 batch_id）时，同帖的登记虽不冲突
     * 却也没有任何机制去补落那次失败——那批增量就<b>凭空消失</b>了，而且毫无报错。
     * 这对「最终一致计数」是最坏的一类缺陷：没人会发现数不对。</p>
     *
     * <p><b>故障注入方式</b>：把 {@code post_counter} <b>改名</b>藏起来，让第 ② 步（UPSERT）
     * 必然失败，第 ① 步（登记）不受影响——精确命中「① 成功 ② 失败」这个窗口。
     * 用 {@code RENAME} 而不是 {@code DROP}：改名<b>不丢数据</b>，改名回来后还能验证
     * 「失败批次可安全重放且不会重复累加」——DROP 会把基线数据一起带走，那条断言就无从谈起。</p>
     */
    @Test
    void applyBatch_upsertFails_dedupRolledBackAndReplaySucceeds() {
        // 先正常落一批，作为「失败前后不该被动过」的基线
        repo.applyBatch(Map.of("p1", new long[]{5, 0, 0, 0}), "b-ok");
        assertEquals(5L, repo.get("p1").likeCount());

        // 注入：让 UPSERT 这一步必然失败（登记这一步不受影响）
        jdbcTemplate.execute("ALTER TABLE post_counter RENAME TO post_counter_hidden");

        assertThrows(Exception.class,
                () -> repo.applyBatch(Map.of("p1", new long[]{7, 0, 0, 0}), "b-fail"),
                "UPSERT 失败必须向上抛出，绝不能被吞掉——吞掉就等于把「可重放」变成「静默丢弃」");

        // 核心断言：失败的那批在去重表里【不留任何痕迹】，因此下个周期可以安全重放。
        // 若这里查到 1 行，说明登记与 UPSERT 没在同一事务里，丢增量窗口仍在。
        assertEquals(0, dedupCount("b-fail"),
                "UPSERT 失败后去重登记必须一并回滚：留痕会让该批增量被永久判定为『已落』→ 永久丢失");
        // 已成功落库的批次不受影响（回滚不能波及别人的事务）
        assertEquals(1, dedupCount("b-ok"));

        // 恢复表后用新批次重放：事务闭合的价值就在「能重放且不会重复累加」
        jdbcTemplate.execute("ALTER TABLE post_counter_hidden RENAME TO post_counter");
        repo.applyBatch(Map.of("p1", new long[]{7, 0, 0, 0}), "b-retry");
        assertEquals(12L, repo.get("p1").likeCount(), "5 + 7：失败批次应可安全重放，且不重复累加");
        assertEquals(1, dedupCount("b-retry"));
    }

    /** 逃生路径（整批重放）逐帖补落后，登记与计数同样保持「要么都成、要么都滚」。 */
    @Test
    void applyBatch_wholeBatchReplay_doesNotDoubleCountAndKeepsDedupConsistent() {
        Map<String, long[]> snap = Map.of(
                "p1", new long[]{5, 0, 0, 0},
                "p2", new long[]{0, 3, 1, 0});
        repo.applyBatch(snap, "batch-1");
        // 整批重放 → 内部走逐帖 safeApply 逃生路径：每帖一个独立事务，已落的被 dedup 拦下
        repo.applyBatch(snap, "batch-1");

        assertEquals(5L, repo.get("p1").likeCount());
        assertEquals(3L, repo.get("p2").commentCount());
        assertEquals(2, dedupCount("batch-1"), "重放不应产生多余的去重行");
    }

    private int dedupCount(String batchId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM counter_flush_dedup WHERE batch_id = ?", Integer.class, batchId);
        return n == null ? 0 : n;
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
