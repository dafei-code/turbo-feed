package com.turbofeed.counter.persist;

import com.turbofeed.shared.model.CounterView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p><b>事务闭合（M2 / 4.2，本次改造的核心）</b>：去重登记与批量 UPSERT <b>必须在同一个
 * 本地事务里</b>。改造前这两步是两个独立的自动提交批次，中间夹着一个<b>永久丢增量</b>的窗口：</p>
 * <pre>
 *   ① dedup 登记成功 ──→ ② UPSERT 失败（连接闪断 / 部分失败）
 *                          ↓
 *       dedup 里已有本批记录 → 下次重放被判为「已落」直接跳过 → 这批增量【永久丢失】
 * </pre>
 * 反过来「先 UPSERT 后登记」也不行：登记失败会让重放时 dedup 查不到，而 UPSERT 是
 * {@code +VALUES()} <b>累加</b>而非覆盖，重放就变成<b>重复累加</b>——把「丢」换成「重」，
 * 而计数重复对业务是硬伤。所以两条路都不能靠顺序解决，只能靠<b>原子性</b>：
 * 两步并入同一事务后，要么都成（计数已加且已登记），要么都滚（下个周期可安全重放）。
 * 两条 SQL 同数据源，纯本地事务即可，不需要任何分布式事务。</p>
 *
 * <p>幂等仍由 {@code counter_flush_dedup(batch_id, post_id)} 主键兜底：它是<b>最后一道</b>防线
 * （防同一 batch 被二次执行），日常的「不丢不重」由上面的事务保证。不同批次的同帖各自合法
 * 落库——{@code batch_id} 不同，去重行不冲突。整批重放（{@code DuplicateKeyException}）
 * 时自动降级为逐帖 {@link #safeApply} 在独立事务里补落。</p>
 *
 * <p><b>为什么用 {@code TransactionTemplate} 而不是 {@code @Transactional}</b>：
 * {@link #safeApply} 是被本类内部调用的，标注 {@code @Transactional} 会踩 Spring 自调用失效
 * 的经典坑（同类内部调用不走代理，事务不生效，而且不报错）。显式用模板对象，
 * 事务边界在代码里看得见，也让「逃生路径必须开新事务」这件事无法被误写成同类调用。</p>
 */
@Repository
public class PostCounterRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;

    public PostCounterRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 批量 UPSERT（MySQL 8 与 H2 MySQL 模式均支持 ON DUPLICATE KEY UPDATE）。
     *  列顺序与 {@code CounterType} 序号一致：like / comment / share / comment_like。 */
    private static final String UPSERT_SQL =
            "INSERT INTO post_counter (post_id, like_count, comment_count, share_count, comment_like_count, updated_at) "
          + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP) "
          + "ON DUPLICATE KEY UPDATE like_count = like_count + VALUES(like_count), "
          + "comment_count = comment_count + VALUES(comment_count), "
          + "share_count = share_count + VALUES(share_count), "
          + "comment_like_count = comment_like_count + VALUES(comment_like_count), "
          + "updated_at = CURRENT_TIMESTAMP";

    /** 去重登记（PK = (batch_id, post_id)）。 */
    private static final String DEDUP_SQL =
            "INSERT INTO counter_flush_dedup (batch_id, post_id, created_at) VALUES (?, ?, ?)";

    private static final String COLUMNS = "post_id, like_count, comment_count, share_count, comment_like_count";

    /**
     * 整批叠加计数增量（批量落库 + 幂等闸门）。
     *
     * @param snapshot 某次 drain 的 post_id -> [likeDelta, commentDelta, shareDelta, commentLikeDelta]
     * @param batchId  本 flush 周期唯一标识（UUID），用于去重与重放保护
     */
    public void applyBatch(Map<String, long[]> snapshot, String batchId) {
        if (snapshot.isEmpty()) {
            return;
        }
        List<String> postIds = new ArrayList<>(snapshot.keySet());
        try {
            // ① + ② 同一事务：要么「已登记且已累加」，要么两者都没发生（下周期可安全重放）。
            //    改造前这下面是两次独立自动提交，中间的丢增量窗口见类注释。
            tx.execute(status -> {
                // ① 批量登记去重（同 batch 被整体重放会撞 PK，由下方 catch 走补落路径）
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
                // ② 批量 UPSERT 增量（O(1) 往返）
                jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        long[] d = snapshot.get(postIds.get(i));
                        ps.setString(1, postIds.get(i));
                        ps.setLong(2, d[0]);
                        ps.setLong(3, d[1]);
                        ps.setLong(4, d[2]);
                        ps.setLong(5, d[3]);
                    }

                    @Override
                    public int getBatchSize() {
                        return postIds.size();
                    }
                });
                return null;
            });
        } catch (DuplicateKeyException e) {
            // 整批为已落批次的重放：上面的事务已整体回滚（① ② 都没落），这里逐帖在
            // 【独立新事务】里补落——已落的被 dedup 拦下，未落的补上。
            for (String pid : postIds) {
                safeApply(pid, snapshot.get(pid), batchId);
            }
        }
        // 其它异常（UPSERT 失败 / 连接中断）不在此捕获：事务已回滚，dedup 与计数都没落，
        // 交由调度器下个周期用新 batch_id 重放。绝不能吞掉——吞掉就等于把「可重放」
        // 变成「静默丢弃」，那正是本次改造要消灭的失效形状。
    }

    /**
     * 单帖幂等补落（整批重放的逃生路径）：登记与 UPSERT 同样并在一个独立事务里。
     *
     * <p>已登记 → 空事务直接提交，不重复累加；UPSERT 失败 → 事务回滚、登记一并作废，
     * 与 {@link #applyBatch} 的批量路径保持同一套「要么都成、要么都滚」语义，
     * 不存在「逃生路径反而更不安全」的裂缝。</p>
     */
    private void safeApply(String postId, long[] d, String batchId) {
        tx.execute(status -> {
            try {
                jdbcTemplate.update(DEDUP_SQL, batchId, postId, Timestamp.from(Instant.now()));
            } catch (DuplicateKeyException e) {
                return null; // 该 (batch, post) 已落，跳过（空事务提交）
            }
            jdbcTemplate.update(UPSERT_SQL, postId, d[0], d[1], d[2], d[3]);
            return null;
        });
    }

    /** 批量回读聚合计数（1 次 IN 查询），供 flush 后修正 L1/L2。空集合返回空 Map。 */
    public Map<String, CounterView> getMany(Collection<String> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = postIds.stream().map(p -> "?").collect(Collectors.joining(","));
        String sql = "SELECT " + COLUMNS + " FROM post_counter "
                + "WHERE post_id IN (" + placeholders + ")";
        List<CounterView> list = jdbcTemplate.query(sql, (rs, i) -> new CounterView(
                rs.getString("post_id"), rs.getLong("like_count"),
                rs.getLong("comment_count"), rs.getLong("share_count"),
                rs.getLong("comment_like_count")), postIds.toArray());
        return list.stream().collect(Collectors.toMap(CounterView::postId, Function.identity()));
    }

    /** 读取某帖聚合计数；无记录返回 {@code null}（调用方转全零视图）。 */
    public CounterView get(String postId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM post_counter WHERE post_id = ?",
                    (rs, i) -> new CounterView(rs.getString("post_id"), rs.getLong("like_count"),
                            rs.getLong("comment_count"), rs.getLong("share_count"),
                            rs.getLong("comment_like_count")),
                    postId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }
}
