package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.review.ReviewTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 复审任务仓储（review_task 单表，ds_0）。
 *
 * <p>抖音式「举报累计 → 人工复核」的队列存储：建单（AUTO_INCREMENT id）、按 media 去重查、
 * 按 status 列队扫描、处置翻终态。所有写均 within 业务事务（与举报/审核状态机同生共死）。</p>
 */
@Repository
public class ReviewTaskRepository {

    /**
     * 显式构造器（不用 {@code @RequiredArgsConstructor}）：本机构建环境对新文件的 Lombok 处理不生效。
     */
    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ReviewTask> MAPPER = (ResultSet rs, int rowNum) -> {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new ReviewTask(
                rs.getLong("id"),
                rs.getString("media_id"),
                rs.getLong("author_id"),
                rs.getString("task_type"),
                rs.getInt("trigger_count"),
                rs.getInt("status"),
                rs.getTimestamp("created_at").toInstant(),
                resolved == null ? null : resolved.toInstant(),
                rs.getString("resolver"));
    };

    public ReviewTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 建一条待复审任务（id 由库 AUTO_INCREMENT 生成）。 */
    public void insert(String mediaId, long authorId, String taskType, int triggerCount) {
        jdbcTemplate.update(
                "INSERT INTO review_task (media_id, author_id, task_type, trigger_count, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 0, ?)",
                mediaId, authorId, taskType, triggerCount, Timestamp.from(Instant.now()));
    }

    /** 该 media 是否已有待复审任务（防重复建单）。 */
    public boolean existsOpenForMedia(String mediaId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_task WHERE media_id = ? AND status = 0",
                Integer.class, mediaId);
        return n != null && n > 0;
    }

    /** 列出某状态任务（按创建时间升序，REVIEWER 队列 oldest-first）。 */
    public List<ReviewTask> findByStatus(int status) {
        return jdbcTemplate.query(
                "SELECT id, media_id, author_id, task_type, trigger_count, status, created_at, resolved_at, resolver "
                        + "FROM review_task WHERE status = ? ORDER BY created_at ASC",
                MAPPER, status);
    }

    /** 按 id 取任务（决定前校验是否存在且仍待复审）。 */
    public ReviewTask findById(long id) {
        List<ReviewTask> list = jdbcTemplate.query(
                "SELECT id, media_id, author_id, task_type, trigger_count, status, created_at, resolved_at, resolver "
                        + "FROM review_task WHERE id = ?",
                MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 处置：把单条任务翻成终态（仅 PENDING 可翻，幂等）。 */
    public void resolve(long id, int status, String resolver) {
        jdbcTemplate.update(
                "UPDATE review_task SET status = ?, resolved_at = ?, resolver = ? WHERE id = ? AND status = 0",
                status, Timestamp.from(Instant.now()), resolver, id);
    }

    /** 处置时顺带清理同 media 的其它待复审任务（防并发重复建单导致的孤儿任务）。 */
    public void resolveAllForMedia(String mediaId, int status, String resolver) {
        jdbcTemplate.update(
                "UPDATE review_task SET status = ?, resolved_at = ?, resolver = ? WHERE media_id = ? AND status = 0",
                status, Timestamp.from(Instant.now()), resolver, mediaId);
    }
}
