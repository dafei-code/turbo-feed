package com.turbofeed.gateway.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 用户举报仓储（report 表，单表，ds_0；MVP 简化，按 media_id 索引）。
 *
 * <p>举报闭环：用户对任意已发布内容举报 → 写本表（status=0 待处理）；高危类目由
 * {@code MediaReviewService#report} 立即下架，普通举报由管理员在
 * {@code /api/admin/media/report-review} 复核（确认违规→TAKEN_DOWN，不成立→驳回）。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ReportRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 写入一条举报（status=0 待处理）。 */
    public void insert(String mediaId, long reporterUserId, String reason) {
        jdbcTemplate.update(
                "INSERT INTO report (media_id, reporter_user_id, reason, status, created_at) "
                        + "VALUES (?, ?, ?, 0, ?)",
                mediaId, reporterUserId, reason, Timestamp.from(Instant.now()));
    }

    /** 处理完标记（confirmed=true 违规成立 status=1 / false 驳回不成立 status=2）。 */
    public void resolve(String mediaId, boolean confirmed) {
        jdbcTemplate.update(
                "UPDATE report SET status = ? WHERE media_id = ? AND status = 0",
                confirmed ? 1 : 2, mediaId);
    }
}
