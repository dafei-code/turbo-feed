package com.turbofeed.gateway.service.comment;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 评论持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p><b>分片路由</b>：{@code comment} 表分片键为 {@code media_id}（独立于 media 按 user_id
 * 分片），保证「读某条内容下所有评论」单分片命中；回复列表按 {@code root_id} 过滤，
 * {@code idx_media_root_created(media_id, root_id, created_at)} 覆盖顶层与楼中楼两种查询。</p>
 *
 * <p><b>commentId 生成</b>：应用层 {@link com.turbofeed.gateway.util.SnowflakeIdGenerator}，
 * 与 {@code UserService.userId} 同源（worker=1, datacenter=1）。ShardingSphere 的
 * {@code keyGenerateStrategy} 仅在 INSERT 不含 comment_id 时兜底，本路径始终显式带值。</p>
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
     * 顶层评论列表（{@code rootId = 0}）。按 {@code created_at} 倒序，分页。
     */
    public List<Comment> listTopLevel(String mediaId, int limit, long offset) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM comment "
                        + "WHERE media_id = ? AND root_id = 0 AND status = ? "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                MAPPER, mediaId, CommentStatus.APPROVED.code(), limit, offset);
    }

    /**
     * 某根评论下的全部回复（{@code root_id = R}）。按 {@code created_at} 正序（楼中楼时间线）。
     */
    public List<Comment> listReplies(String mediaId, long rootId, int limit, long offset) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM comment "
                        + "WHERE media_id = ? AND root_id = ? AND status = ? "
                        + "ORDER BY created_at ASC LIMIT ? OFFSET ?",
                MAPPER, mediaId, rootId, CommentStatus.APPROVED.code(), limit, offset);
    }

    public long countTopLevel(String mediaId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE media_id = ? AND root_id = 0 AND status = ?",
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

    /**
     * 点赞 +1（用户维度去重留作扩展点：当前每次调用 +1，骨架阶段不维护「已点」集合）。
     * 带 {@code media_id} 分片键，仅本人评论可被点赞（用户身份校验由 controller 负责）。
     */
    public int incrementLike(String mediaId, long commentId) {
        return jdbcTemplate.update(
                "UPDATE comment SET like_count = like_count + 1 WHERE comment_id = ? AND media_id = ?",
                commentId, mediaId);
    }

    /** 父评论的位置（commentId + rootId），用于派生新回复的 rootId。 */
    public record ParentLocation(long commentId, long rootId) {
    }
}
