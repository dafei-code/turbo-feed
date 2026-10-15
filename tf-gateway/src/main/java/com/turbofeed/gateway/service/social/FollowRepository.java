package com.turbofeed.gateway.service.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 关注关系持久层（JDBC，经 ShardingSphere 路由到分片）。
 *
 * <p><b>分片路由</b>：{@code user_follow} 分片键 user_id、{@code user_fans} 分片键 author_id，
 * 均 hashMod128，与 media / comment 同规模。写路径双表 INSERT（我关注谁 + 谁关注我）
 * 单分片命中。本表为<b>持久化兜底</b>——读路径以 Redis ZSET（tf:follow / tf:follower）为权威，
 * 重启后由管理端点从本表重建 Redis。</p>
 *
 * <p><b>幂等</b>：INSERT ... ON DUPLICATE KEY UPDATE（PK = (user_id, author_id) / (author_id, user_id)），
 * 重复关注只刷新 mutual/status，不插重复行；取消关注走 UPDATE status=0（逻辑删除，保留审计），
 * 重新关注命中重复键恢复 status=1。</p>
 */
@Repository
public class FollowRepository {

    private static final Logger log = LoggerFactory.getLogger(FollowRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public FollowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 落库「我关注了 authorId」（互关态 mutual 由 Redis 反查得出）。 */
    public void upsertFollow(long userId, long authorId, boolean mutual) {
        jdbcTemplate.update(
                "INSERT INTO user_follow (user_id, author_id, mutual, status, created_at) "
                        + "VALUES (?, ?, ?, 1, NOW()) "
                        + "ON DUPLICATE KEY UPDATE mutual = VALUES(mutual), status = 1, updated_at = NOW()",
                userId, authorId, mutual ? 1 : 0);
    }

    /** 落库「authorId 多了一个粉丝 userId」。 */
    public void upsertFan(long authorId, long userId, boolean mutual) {
        jdbcTemplate.update(
                "INSERT INTO user_fans (author_id, user_id, mutual, status, created_at) "
                        + "VALUES (?, ?, ?, 1, NOW()) "
                        + "ON DUPLICATE KEY UPDATE mutual = VALUES(mutual), status = 1, updated_at = NOW()",
                authorId, userId, mutual ? 1 : 0);
    }

    /** 取消「我关注了 authorId」（逻辑删除，保留行）。 */
    public void cancelFollow(long userId, long authorId) {
        jdbcTemplate.update(
                "UPDATE user_follow SET status = 0, updated_at = NOW() WHERE user_id = ? AND author_id = ?",
                userId, authorId);
    }

    /** 取消「authorId 的粉丝 userId」。 */
    public void cancelFan(long authorId, long userId) {
        jdbcTemplate.update(
                "UPDATE user_fans SET status = 0, updated_at = NOW() WHERE author_id = ? AND user_id = ?",
                authorId, userId);
    }

    /** 重建用：取某用户关注的全部 authorId（status=1），单分片命中。 */
    public List<Long> findFollows(long userId) {
        return jdbcTemplate.query(
                "SELECT author_id FROM user_follow WHERE user_id = ? AND status = 1",
                (rs, rn) -> rs.getLong("author_id"), userId);
    }

    /** 重建用：取某用户的全部粉丝 userId（status=1），单分片命中。 */
    public List<Long> findFans(long authorId) {
        return jdbcTemplate.query(
                "SELECT user_id FROM user_fans WHERE author_id = ? AND status = 1",
                (rs, rn) -> rs.getLong("user_id"), authorId);
    }
}
