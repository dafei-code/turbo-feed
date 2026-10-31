package com.turbofeed.gateway.service.notify;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 通知持久层（JDBC，经 ShardingSphere 路由到 {@code user_id} 分片）。
 *
 * <p><b>分片路由</b>：{@code user_notification} 分片键 {@code user_id}（HASH_MOD 128，与 user_follow
 * 同规模），保证「读某用户全部通知」单分片命中。</p>
 *
 * <p><b>id 生成</b>：应用层 {@link com.turbofeed.gateway.util.SnowflakeIdGenerator}（与 media_favorite
 * 同源）；INSERT 显式带值。</p>
 */
@Repository
@RequiredArgsConstructor
public class NotificationJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS =
            "id, user_id, type, actor_id, target_type, target_id, summary, ref_payload, is_read, created_at, revision";

    private static final RowMapper<Notification> ROW_MAPPER = (rs, i) -> new Notification(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("type"),
            rs.getObject("actor_id", Long.class),
            rs.getString("target_type"),
            rs.getString("target_id"),
            rs.getString("summary"),
            rs.getString("ref_payload"),
            rs.getBoolean("is_read"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("revision"));

    /** 落库一条通知。 */
    public void insert(Notification n) {
        jdbcTemplate.update(
                "INSERT INTO user_notification (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                n.id(), n.userId(), n.type(), n.actorId(), n.targetType(), n.targetId(),
                n.summary(), n.refPayload(), n.read() ? 1 : 0, Timestamp.from(n.createdAt()), n.revision());
    }

    /**
     * 收件箱列表（按接收方 + 游标倒序分页）。
     *
     * <p><b>keyset 游标</b>：{@code created_at, id} 倒序；{@code cursorTs > 0} 时取
     * {@code created_at < cursorTs}（排它，跳过上一页末位），首页 {@code cursorTs <= 0} 取全部。
     * 注：{@code created_at} 为 DATETIME（秒精度），同秒两条边界可能漏一页末条，低频场景可接受（刷新即现）。</p>
     */
    public List<Notification> listByUser(long userId, long cursorTs, int limit) {
        if (cursorTs <= 0) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM user_notification WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    ROW_MAPPER, userId, limit);
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM user_notification WHERE user_id = ? AND created_at < ? ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW_MAPPER, userId, Timestamp.from(java.time.Instant.ofEpochMilli(cursorTs)), limit);
    }

    /** 未读条数（DB 为真相源，Redis 计数失效时回源）。 */
    public long countUnread(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM user_notification WHERE user_id = ? AND is_read = 0", Integer.class, userId);
        return n == null ? 0L : n;
    }

    /**
     * 标记已读（按 id 列表，带分片键单分片命中）。
     *
     * @return 实际置为已读的条数（已读的不再计数）
     */
    public int markRead(long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                "UPDATE user_notification SET is_read = 1, revision = revision + 1 WHERE user_id = ? AND id IN ("
                        + joinPlaceholders(ids.size()) + ") AND is_read = 0",
                args(userId, ids));
    }

    /** 全部标记为已读（可选按类型，带分片键单分片命中）。 */
    public int markAllRead(long userId, String type) {
        if (type == null || type.isBlank()) {
            return jdbcTemplate.update(
                    "UPDATE user_notification SET is_read = 1, revision = revision + 1 WHERE user_id = ? AND is_read = 0",
                    userId);
        }
        return jdbcTemplate.update(
                "UPDATE user_notification SET is_read = 1, revision = revision + 1 WHERE user_id = ? AND type = ? AND is_read = 0",
                userId, type);
    }

    private static String joinPlaceholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    private static Object[] args(long userId, List<Long> ids) {
        Object[] a = new Object[ids.size() + 1];
        a[0] = userId;
        for (int i = 0; i < ids.size(); i++) {
            a[i + 1] = ids.get(i);
        }
        return a;
    }
}
