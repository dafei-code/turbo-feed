package com.turbofeed.counter.persist;

import com.turbofeed.shared.model.CounterView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 聚合计数持久层（tf-counter 自有 {@code post_counter} 表，经应用 DataSource 路由）。
 *
 * <p>定位：计数服务的<b>持久真相源（L3）</b>。与网关实时计数（Redis 64 槽 Set）解耦——
 * 这里只存「最终一致聚合值」，由 {@link CounterBufferService} 的定时 flush 批量写入，
 * 不承接任何实时读，因此不存在 MySQL 热点行锁问题（写是低频批量、按 post_id 分散）。</p>
 *
 * <p><b>落库策略（跨库可移植）</b>：{@code applyDelta} 先 {@code UPDATE ... +delta}，
 * 命中 0 行再 {@code INSERT}。避开 {@code ON DUPLICATE KEY UPDATE}（MySQL）/ {@code MERGE}
 * （H2）的方言分歧，H2(MySQL 模式) 与 MySQL 8 行为一致。P1-2 单实例写入，无跨实例并发竞争。</p>
 */
@Repository
public class PostCounterRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostCounterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 给某帖叠加一组计数增量（批量落库用）。全 0 直接跳过。 */
    public void applyDelta(String postId, long dLike, long dComment, long dShare) {
        if (dLike == 0 && dComment == 0 && dShare == 0) {
            return;
        }
        int updated = jdbcTemplate.update(
                "UPDATE post_counter SET like_count = like_count + ?, comment_count = comment_count + ?, "
                        + "share_count = share_count + ?, updated_at = CURRENT_TIMESTAMP WHERE post_id = ?",
                dLike, dComment, dShare, postId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO post_counter (post_id, like_count, comment_count, share_count, updated_at) "
                            + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    postId, dLike, dComment, dShare);
        }
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
