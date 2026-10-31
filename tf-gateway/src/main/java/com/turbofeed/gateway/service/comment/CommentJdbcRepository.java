package com.turbofeed.gateway.service.comment;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 评论持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p><b>分片路由</b>：{@code comment} 表分片键为 {@code media_id}（独立于 media 按 user_id
 * 分片），保证「读某条内容下所有评论」单分片命中；回复列表按 {@code root_id} 过滤，
 * {@code idx_media_root_created(media_id, root_id, created_at, comment_id)} 覆盖顶层与楼中楼两种查询。</p>
 *
 * <p><b>深分页</b>：列表改为 keyset 游标（{@code (created_at, comment_id)} 范围查询），替代
 * {@code OFFSET}——{@code OFFSET 100000} 需先扫描丢弃前 10 万行，数据量大时随深度线性退化；
 * keyset 每次只从游标之后取 {@code LIMIT} 行，复杂度恒定为 O(LIMIT)，与翻页深度无关。</p>
 *
 * <p><b>commentId 生成</b>：应用层 {@link com.turbofeed.gateway.util.SnowflakeIdGenerator}，
 * 与 {@code UserService.userId} 同源（worker=1, datacenter=1）。ShardingSphere 的
 * {@code keyGenerateStrategy} 仅在 INSERT 不含 comment_id 时兜底，本路径始终显式带值。</p>
 *
 * <p><b>点赞计数</b>：不再在 DB 做 {@code like_count+1} 热行更新（高并发写放大 + 行锁），改由
 * {@link CommentLikeService} 的 Redis 64 槽 Set 权威计数，DB {@code like_count} 仅作灾备冗余列。</p>
 */
@Repository
@RequiredArgsConstructor
public class CommentJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS =
            "comment_id, media_id, user_id, root_id, parent_id, content, status, like_count, created_at";

    private static final RowMapper<Comment> MAPPER = (rs, rn) -> new Comment(
            rs.getLong("comment_id"),
            rs.getString("media_id"),
            rs.getLong("user_id"),
            rs.getLong("root_id"),
            rs.getLong("parent_id"),
            rs.getString("content"),
            CommentStatus.fromCode(rs.getInt("status")),
            rs.getInt("like_count"),
            rs.getTimestamp("created_at").toInstant());

    /** 落库一条评论（commentId 由调用方生成）。 */
    public void insert(Comment c) {
        jdbcTemplate.update(
                "INSERT INTO comment (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.commentId(), c.mediaId(), c.userId(), c.rootId(), c.parentId(),
                c.content(), c.status().code(), c.likeCount(), Timestamp.from(c.createdAt()));
    }

    /**
     * 幂等落库（MQ 消费者用）：{@code comment_id} 为主键，重复事件 {@code INSERT IGNORE} 静默跳过，
     * 保证 RocketMQ at-least-once 投递下（网络重投/消费重试）同评论只落一次，不报错。
     */
    public void insertIgnore(Comment c) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO comment (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.commentId(), c.mediaId(), c.userId(), c.rootId(), c.parentId(),
                c.content(), c.status().code(), c.likeCount(), Timestamp.from(c.createdAt()));
    }

    /**
     * 顶层评论列表（{@code rootId = 0}）最新一页（无游标，按 {@code created_at} 倒序）。
     * 走索引 {@code idx_media_root_created} 的有序前缀，仅读 {@code LIMIT} 行，无深扫。
     */
    public List<Comment> listTopLevelFresh(String mediaId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM comment "
                        + "WHERE media_id = ? AND root_id = 0 AND status = ? "
                        + "ORDER BY created_at DESC, comment_id DESC LIMIT ?",
                MAPPER, mediaId, CommentStatus.APPROVED.code(), limit);
    }

    /**
     * 顶层评论列表 keyset 游标页（{@code created_at, comment_id} 严格早于游标，倒序）。
     * 复杂度 O(LIMIT)，与翻页深度无关。
     */
    public List<Comment> listTopLevelKeyset(String mediaId, int limit, Instant cursorCreatedAt, long cursorCommentId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM comment "
                        + "WHERE media_id = ? AND root_id = 0 AND status = ? "
                        + "AND (created_at < ? OR (created_at = ? AND comment_id < ?)) "
                        + "ORDER BY created_at DESC, comment_id DESC LIMIT ?",
                MAPPER, mediaId, CommentStatus.APPROVED.code(),
                Timestamp.from(cursorCreatedAt), Timestamp.from(cursorCreatedAt), cursorCommentId, limit);
    }

    /**
     * 某根评论下的全部回复（{@code root_id = R}）最新一页（无游标，按 {@code created_at} 正序，楼中楼时间线）。
     */
    public List<Comment> listRepliesFresh(String mediaId, long rootId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM comment "
                        + "WHERE media_id = ? AND root_id = ? AND status = ? "
                        + "ORDER BY created_at ASC, comment_id ASC LIMIT ?",
                MAPPER, mediaId, rootId, CommentStatus.APPROVED.code(), limit);
    }

    /**
     * 某根评论下的回复 keyset 游标页（{@code created_at, comment_id} 严格晚于游标，正序）。
     */
    public List<Comment> listRepliesKeyset(String mediaId, long rootId, int limit, Instant cursorCreatedAt, long cursorCommentId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM comment "
                        + "WHERE media_id = ? AND root_id = ? AND status = ? "
                        + "AND (created_at > ? OR (created_at = ? AND comment_id > ?)) "
                        + "ORDER BY created_at ASC, comment_id ASC LIMIT ?",
                MAPPER, mediaId, rootId, CommentStatus.APPROVED.code(),
                Timestamp.from(cursorCreatedAt), Timestamp.from(cursorCreatedAt), cursorCommentId, limit);
    }

    /**
     * 按 commentId 批量取正文（热头 ZSET 桥接用）。带 {@code media_id} 分片键，单分片命中
     * （{@code WHERE media_id=? AND comment_id IN(...)} 由 media_id 决定分片，IN 在该分片内做本地索引扫描，非广播）。
     */
    public List<Comment> listByIds(String mediaId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = ids.stream().map(i -> "?").collect(java.util.stream.Collectors.joining(",", "(", ")"));
        String sql = "SELECT " + COLUMNS + " FROM comment WHERE media_id = ? AND comment_id IN " + placeholders;
        Object[] args = new Object[ids.size() + 1];
        args[0] = mediaId;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        return jdbcTemplate.query(sql, MAPPER, args);
    }

    /** 某内容下评论总数（顶层 + 回复，含 APPROVED）。冷启动 / Redis 缺失时由 {@link CommentCountService} 调用。 */
    public long countAll(String mediaId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE media_id = ? AND status = ?",
                Long.class, mediaId, CommentStatus.APPROVED.code());
        return c == null ? 0L : c;
    }

    /**
     * 查询父评论的最小必要信息（仅用于计算新回复的 rootId）。
     *
     * <p>带 {@code media_id} 分片键，单分片命中，避免 {@code WHERE comment_id = ?} 的全分片广播。
     * 客户端调用回复接口时已持有 {@code mediaId}，透传给此处即可。</p>
     */
    public Optional<ParentLocation> findParent(String mediaId, long parentId) {
        List<ParentLocation> list = jdbcTemplate.query(
                "SELECT comment_id, root_id FROM comment WHERE comment_id = ? AND media_id = ?",
                (rs, rn) -> new ParentLocation(rs.getLong("comment_id"), rs.getLong("root_id")),
                parentId, mediaId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 父评论的位置（commentId + rootId），用于派生新回复的 rootId。 */
    public record ParentLocation(long commentId, long rootId) {
    }

    /**
     * 取父评论作者（回复通知接收方）。带 {@code media_id} 分片键，单分片命中，
     * 避免 {@code WHERE comment_id = ?} 的全分片广播。
     *
     * @return 父评论的 {@code user_id}；不存在 / 查不到返回 {@code null}
     */
    public Long findAuthorByCommentId(String mediaId, long commentId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT user_id FROM comment WHERE comment_id = ? AND media_id = ?",
                (rs, rn) -> rs.getLong("user_id"), commentId, mediaId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
