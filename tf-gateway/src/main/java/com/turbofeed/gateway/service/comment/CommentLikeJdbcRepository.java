package com.turbofeed.gateway.service.comment;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

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

    /**
     * 查某评论下全部点赞用户（A4 灾备重建用，带分片键 {@code media_id} 单分片命中）。
     *
     * <p>本表是「Redis 为权威、DB 为灾备」中的灾备侧：{@code CommentLikeService#rebuildFromDb}
     * 据此恢复 64 槽去重集合与计数 Key。改造前本表只写不读，灾备承诺无法执行。</p>
     */
    public List<Long> listUserIds(String mediaId, long commentId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id FROM comment_like WHERE media_id = ? AND comment_id = ?",
                Long.class, mediaId, commentId);
    }

    /**
     * 某用户是否已赞过某评论（去重集合 TTL 回收后的兜底校验，带分片键 {@code media_id} 单分片命中）。
     *
     * <p>用途与口径同 {@code MediaLikeJdbcRepository#existsLike}：去重集合是 Redis 侧权威，
     * 但有 TTL；集合被回收后曾点赞的用户再点一次会被误判为新赞 → 计数虚增。
     * <b>只用于「拦截」不用于「放行」</b>——本表异步写入（fail-open），查不到不代表没赞过，
     * 只能按新赞放行（即回到改造前行为），因此是单调改善。</p>
     */
    public boolean existsLike(String mediaId, long commentId, long userId) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM comment_like WHERE media_id = ? AND comment_id = ? AND user_id = ?",
                    Integer.class, mediaId, commentId, userId);
            return n != null && n > 0;
        } catch (Exception e) {
            // fail-open：灾备不可用时按「没赞过」放行（= 改造前行为）
            return false;
        }
    }
}
