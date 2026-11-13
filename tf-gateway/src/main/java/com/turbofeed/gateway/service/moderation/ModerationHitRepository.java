package com.turbofeed.gateway.service.moderation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 内容安全命中审计持久层（JDBC，单表落 ds_0）。
 *
 * <p>只写多读少：写路径每次命中插一行；管理后台按 action / reviewer_result 分页查询。
 * 表登记见 shardingsphere-config*.yaml 的 !SINGLE 段（content_moderation_hit）。</p>
 */
@Repository
public class ModerationHitRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModerationHitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ModerationHit> MAPPER = (rs, rn) -> {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp reviewed = rs.getTimestamp("reviewed_at");
        long taskId = rs.getLong("review_task_id");
        return new ModerationHit(
                rs.getLong("id"),
                ContentScene.valueOf(rs.getString("scene")),
                rs.getLong("user_id"),
                rs.getString("target_id"),
                rs.getString("snippet_masked"),
                rs.getString("matched_word"),
                rs.getString("category"),
                ModerationAction.valueOf(rs.getString("action")),
                rs.wasNull() ? null : taskId,
                rs.getString("reviewer_result"),
                created == null ? Instant.EPOCH : created.toInstant(),
                reviewed == null ? null : reviewed.toInstant());
    };

    /**
     * 记录一次命中（写路径调用，fail-open：调用方负责 try/catch）。
     *
     * @param reviewTaskId REVIEW 动作时关联的人审任务 ID；BLOCK/LIMIT 传 null
     * @return 生成的审计行 ID；调用方据此把 ReviewTask ID 回填
     */
    public long insert(ContentScene scene, long userId, String targetId, String snippetMasked,
                       String matchedWord, String category, ModerationAction action, Long reviewTaskId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO content_moderation_hit "
                            + "(scene, user_id, target_id, snippet_masked, matched_word, category, action, review_task_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scene.name());
            ps.setLong(2, userId);
            ps.setString(3, targetId);
            ps.setString(4, snippetMasked);
            ps.setString(5, matchedWord);
            ps.setString(6, category);
            ps.setString(7, action.name());
            if (reviewTaskId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, reviewTaskId);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 人审裁决后回填结论。 */
    public void updateReviewerResult(long id, String result) {
        jdbcTemplate.update(
                "UPDATE content_moderation_hit SET reviewer_result = ?, reviewed_at = NOW() WHERE id = ?",
                result, id);
    }

    /** 把后续创建的 ReviewTask ID 回填到审计行（CommentService 建任务后调用）。 */
    public void updateReviewTaskId(long hitId, long reviewTaskId) {
        jdbcTemplate.update(
                "UPDATE content_moderation_hit SET review_task_id = ? WHERE id = ?",
                reviewTaskId, hitId);
    }

    /** 管理后台分页：按动作过滤；未裁决（reviewer_result IS NULL）是默认队列。 */
    public List<ModerationHit> list(String action, String reviewerResult, int limit, long offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, scene, user_id, target_id, snippet_masked, matched_word, category, "
                        + "action, review_task_id, reviewer_result, created_at, reviewed_at "
                        + "FROM content_moderation_hit WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            args.add(action);
        }
        if (reviewerResult != null && !reviewerResult.isBlank()) {
            sql.append(" AND reviewer_result = ?");
            args.add(reviewerResult);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_moderation_hit", Long.class);
        return c == null ? 0L : c;
    }
}
