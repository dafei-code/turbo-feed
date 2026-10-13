package com.turbofeed.gateway.service.like;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * 点赞持久层（JDBC，经 ShardingSphere DataSource 路由到分片）。
 *
 * <p><b>分片路由</b>：{@code media_like} 表分片键为 {@code post_id}（与公域时间线幂等键
 * {@code timelineKey} 口径一致），{@code hashMod4} 与 {@code comment} 表同策略，保证「读某条
 * 内容下所有点赞」单分片命中。</p>
 *
 * <p><b>id 生成</b>：应用层 {@link com.turbofeed.gateway.util.SnowflakeIdGenerator}，与
 * {@code CommentJdbcRepository} 同源（worker=1, datacenter=1）。INSERT 始终显式带值。</p>
 */
@Repository
@RequiredArgsConstructor
public class MediaLikeJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "id, post_id, user_id, created_at";

    /** 落库一条点赞（赞时 INSERT）。 */
    public void insert(MediaLike like) {
        jdbcTemplate.update(
                "INSERT INTO media_like (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                like.id(), like.postId(), like.userId(), Timestamp.from(like.createdAt()));
    }

    /** 取消点赞（按 post_id + user_id 删除，带分片键单分片命中）。 */
    public int delete(String postId, long userId) {
        return jdbcTemplate.update(
                "DELETE FROM media_like WHERE post_id = ? AND user_id = ?",
                postId, userId);
    }
}
