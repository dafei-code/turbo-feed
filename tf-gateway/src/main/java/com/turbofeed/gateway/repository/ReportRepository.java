package com.turbofeed.gateway.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** 写入一条举报（status=0 待处理），携带类目（P1-1）。 */
    public void insert(String mediaId, long reporterUserId, String reason, int category) {
        jdbcTemplate.update(
                "INSERT INTO report (media_id, reporter_user_id, reason, category, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 0, ?)",
                mediaId, reporterUserId, reason, category, Timestamp.from(Instant.now()));
    }

    /**
     * 取某内容全部待处理举报的 (举报人, 类目) 列表（P1 #131）。
     *
     * <p><b>为什么不在 SQL 里 JOIN reporter_credit 做信用过滤</b>：report 按 media_id 分片（落 ds_0/ds_1），
     * reporter_credit 是 {@code !SINGLE} 单表（仅 ds_0）——两者跨物理库，ShardingSphere 不保证下推成单条
     * JOIN。故这里只取原始行，信用过滤（{@code reporter_credit.credibility >= floor}）放在服务侧 Java 过滤，
     * 既安全又清晰（见 {@code MediaReviewService#maybeEscalateToReviewTask}）。</p>
     */
    public List<PendingReport> pendingByMedia(String mediaId) {
        return jdbcTemplate.query(
                "SELECT reporter_user_id, category FROM report WHERE media_id = ? AND status = 0",
                (ResultSet rs, int rn) -> new PendingReport(rs.getLong("reporter_user_id"), rs.getInt("category")),
                mediaId);
    }

    /** 同举报人是否已有该内容的待处理举报（P1 #131 去重，防同一用户狂点刷起复审台）。 */
    public boolean existsPending(String mediaId, long reporterUserId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report WHERE media_id = ? AND reporter_user_id = ? AND status = 0",
                Integer.class, mediaId, reporterUserId);
        return n != null && n > 0;
    }

    /** 取某内容全部待处理举报的举报人（去重），用于举报处置后回写举报人信用（P1 #131）。 */
    public List<Long> findReporterUserIds(String mediaId) {
        return jdbcTemplate.query(
                "SELECT DISTINCT reporter_user_id FROM report WHERE media_id = ? AND status = 0",
                (ResultSet rs, int rn) -> rs.getLong("reporter_user_id"),
                mediaId);
    }

    /** 待处理举报的 (举报人, 类目) 元组（P1 #131，配合服务侧信用过滤）。 */
    public record PendingReport(long reporterUserId, int category) {
    }

    /** 处理完标记（confirmed=true 违规成立 status=1 / false 驳回不成立 status=2）。 */
    public void resolve(String mediaId, boolean confirmed) {
        jdbcTemplate.update(
                "UPDATE report SET status = ? WHERE media_id = ? AND status = 0",
                confirmed ? 1 : 2, mediaId);
    }
}
