package com.turbofeed.counter.persist;

import com.turbofeed.shared.model.CounterView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 聚合计数持久层（tf-counter 自有 {@code post_counter} 表，经应用 DataSource 路由）。
 *
 * <p>定位：计数服务的<b>持久真相源（L3）</b>。与网关实时计数（Redis 64 槽 Set）解耦——
 * 这里只存「最终一致聚合值」，由缓冲的定时 flush 批量写入，不承接任何实时读，
 * 因此不存在 MySQL 热点行锁问题（写是低频批量、按 post_id 分散）。</p>
 *
 * <p><b>批量落库 + 幂等（应对分布式 flush）</b>：{@link #applyBatch} 把一整个 flush 周期的
 * 增量一次性 {@code INSERT ... ON DUPLICATE KEY UPDATE ... + VALUES(...)} 批量 UPSERT，
 * 把原先「逐帖串行 UPDATE + 逐帖串行 SELECT」的 O(P) 往返压缩成<b>常数级往返</b>
 * （1 次去重登记 + 1 次批量 UPSERT），解决「数据量大时可行性」问题。</p>
 *
 * <p>幂等由 {@code counter_flush_dedup(batch_id, post_id)} 主键保证：每周期生成唯一
 * {@code batch_id}，逐帖先登记；同一 (batch_id, post_id) 再次到达（ShedLock 锁过期续跑 /
 * 进程崩溃重试）直接跳过 UPSERT，<b>绝不重复累加</b>。不同批次的同帖各自合法落库——因为
 * {@code batch_id} 不同，去重行不冲突。批量登记若整体命中 DuplicateKeyException（整批重放），
 * 自动降级为逐帖 {@link #safeApply} 补落，保证「部分已落、部分未落」的故障恢复也安全。</p>
 */
@Repository
public class PostCounterRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostCounterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 批量 UPSERT（MySQL 8 与 H2 MySQL 模式均支持 ON DUPLICATE KEY UPDATE）。 */
    private static final String UPSERT_SQL =
            "INSERT INTO post_counter (post_id, like_count, comment_count, share_count, updated_at) "
          + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) "
          + "ON DUPLICATE KEY UPDATE like_count = like_count + VALUES(like_count), "
          + "comment_count = comment_count + VALUES(comment_count), "
          + "share_count = share_count + VALUES(share_count), updated_at = CURRENT_TIMESTAMP";

    /** 去重登记（PK = (batch_id, post_id)）。 */
    private static final String DEDUP_SQL =
            "INSERT INTO counter_flush_dedup (batch_id, post_id, created_at) VALUES (?, ?, ?)";

    /**
     * 整批叠加计数增量（批量落库 + 幂等闸门）。
     *
     * @param snapshot 某次 drain 的 post_id -> [likeDelta, commentDelta, shareDelta]
     * @param batchId  本 flush 周期唯一标识（UUID），用于去重与重放保护
     */
    public void applyBatch(Map<String, long[]> snapshot, String batchId) {
        if (snapshot.isEmpty()) {
            return;
        }
        List<String> postIds = new ArrayList<>(snapshot.keySet());
        // 阶段一：批量登记去重。同一 batch 被整体重放会触发 DuplicateKeyException。
        try {
            jdbcTemplate.batchUpdate(DEDUP_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ps.setString(1, batchId);
                    ps.setString(2, postIds.get(i));
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                }

                @Override
                public int getBatchSize() {
                    return postIds.size();
                }
            });
        } catch (DuplicateKeyException e) {
            // 整批为已落批次的重放 → 逐帖安全补 apply（已落的被 dedup 拦下，未落的补上）
            for (String pid : postIds) {
                safeApply(pid, snapshot.get(pid), batchId);
            }
            return;
        }
        // 阶段二：批量 UPSERT 增量（O(1) 往返）
        jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                long[] d = snapshot.get(postIds.get(i));
                ps.setString(1, postIds.get(i));
                ps.setLong(2, d[0]);
                ps.setLong(3, d[1]);
                ps.setLong(4, d[2]);
            }

            @Override
            public int getBatchSize() {
                return postIds.size();
            }
        });
    }

    /** 单帖幂等补落：去重登记成功才 UPSERT，已登记则跳过。 */
    private void safeApply(String postId, long[] d, String batchId) {
        try {
            jdbcTemplate.update(DEDUP_SQL, batchId, postId, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException e) {
            return; // 该 (batch, post) 已落，跳过
        }
        jdbcTemplate.update(UPSERT_SQL, postId, d[0], d[1], d[2]);
    }

    /** 批量回读聚合计数（1 次 IN 查询），供 flush 后修正 L1/L2。空集合返回空 Map。 */
    public Map<String, CounterView> getMany(Collection<String> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = postIds.stream().map(p -> "?").collect(Collectors.joining(","));
        String sql = "SELECT post_id, like_count, comment_count, share_count FROM post_counter "
                + "WHERE post_id IN (" + placeholders + ")";
        List<CounterView> list = jdbcTemplate.query(sql, (rs, i) -> new CounterView(
                rs.getString("post_id"), rs.getLong("like_count"),
                rs.getLong("comment_count"), rs.getLong("share_count")), postIds.toArray());
        return list.stream().collect(Collectors.toMap(CounterView::postId, Function.identity()));
    }

    /** 读取某帖聚合计数；无记录返回 {@code null}（调用方转全零视图）。 */
    public CounterView get(String postId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT post_id, like_count, comment_count, share_count FROM post_counter WHERE post_id = ?",
                    (rs, i) -> new CounterView(rs.getString("post_id"), rs.getLong("like_count"),
                            rs.getLong("comment_count"), rs.getLong("share_count")),
                    postId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }
}
