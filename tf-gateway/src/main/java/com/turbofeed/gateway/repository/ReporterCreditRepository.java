package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.review.credit.ReporterCredit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 举报人信用仓储（reporter_credit 单表，ds_0 = turbo_feed_1）。
 *
 * <p>与 {@link AccountHealthRepository} 同套路（单表、JdbcTemplate、显式构造器、不依赖 Lombok）。
 * 该表经 ShardingSphere {@code !SINGLE} 托管（见 shardingsphere-config*.yml）。</p>
 */
@Repository
public class ReporterCreditRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 显式构造器注入（不用 {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    public ReporterCreditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 读取举报人信用（无记录返回 {@link Optional#empty()}，由调用方按满分 100 处理）。 */
    public Optional<ReporterCredit> get(long userId) {
        return jdbcTemplate.query(
                "SELECT user_id, total_reports, upheld, rejected, credibility, "
                        + "last_report_at, created_at, updated_at FROM reporter_credit WHERE user_id = ?",
                (ResultSet rs, int rn) -> new ReporterCredit(
                        rs.getLong("user_id"),
                        rs.getInt("total_reports"),
                        rs.getInt("upheld"),
                        rs.getInt("rejected"),
                        rs.getInt("credibility"),
                        rs.getTimestamp("last_report_at") == null
                                ? null : rs.getTimestamp("last_report_at").toInstant(),
                        rs.getTimestamp("created_at") == null
                                ? null : rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at") == null
                                ? null : rs.getTimestamp("updated_at").toInstant()),
                userId).stream().findFirst();
    }

    /** 读取信用分（无记录 → 默认满分 100，即「无罪推定」）。 */
    public int credibilityOf(long userId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT credibility FROM reporter_credit WHERE user_id = ?", Integer.class, userId);
        return v == null ? 100 : v;
    }

    /**
     * 落地信用变更（upsert）：用 {@code ON DUPLICATE KEY UPDATE} 而非「先查后更」，
     * 避免并发下两次举报互相覆盖计数/信用。
     */
    public void apply(long userId, int totalReports, int upheld, int rejected,
                      int credibility, Instant lastReportAt) {
        jdbcTemplate.update(
                "INSERT INTO reporter_credit (user_id, total_reports, upheld, rejected, "
                        + "credibility, last_report_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE total_reports = VALUES(total_reports), "
                        + "upheld = VALUES(upheld), rejected = VALUES(rejected), "
                        + "credibility = VALUES(credibility), "
                        + "last_report_at = VALUES(last_report_at), "
                        + "updated_at = VALUES(updated_at)",
                userId,
                totalReports,
                upheld,
                rejected,
                credibility,
                lastReportAt == null ? null : Timestamp.from(lastReportAt),
                Timestamp.from(Instant.now()));
    }
}
