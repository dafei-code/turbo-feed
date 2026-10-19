package com.turbofeed.gateway.service.comment;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * 评论点赞持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p><b>分片路由</b>：{@code comment_like} 表分片键为 {@code media_id}（与 {@code comment} 表
 * 同策略、同规模 hashMod128），保证「读某条内容下所有点赞」单分片命中；所有写/删语句都带
 * {@code media_id}，避免全分片广播。</p>
 *
 * <p>去重权威在 Redis（{@link CommentLikeService} 的 64 槽 Set），本表为灾备；主键
 * {@code (comment_id, user_id)} 兜底并发重复。</p>
 */
@Repository
@RequiredArgsConstructor
public class CommentLikeJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "id, comment_id, user_id, media_id, created_at";

    /** 落库一条点赞（赞时 INSERT，带分片键 media_id）。 */
    public void insert(CommentLike like) {
        jdbcTemplate.update(
                "INSERT INTO comment_like (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                like.id(), like.commentId(), like.userId(), like.mediaId(), Timestamp.from(like.createdAt()));
    }

    /** 取消点赞（按 comment_id + user_id + media_id 删除，带分片键单分片命中）。 */
    public int delete(long commentId, long userId, String mediaId) {
        return jdbcTemplate.update(
                "DELETE FROM comment_like WHERE comment_id = ? AND user_id = ? AND media_id = ?",
                commentId, userId, mediaId);
    }
}
