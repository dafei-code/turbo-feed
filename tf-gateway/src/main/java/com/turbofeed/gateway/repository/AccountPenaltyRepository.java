package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.penalty.AccountPenalty;
import com.turbofeed.gateway.service.penalty.AccountPenaltyStatus;
import com.turbofeed.gateway.service.penalty.ViolationCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 账号处罚态仓储（account_penalty 表）。
 *
 * <p><b>与 account_credit 的关系</b>：两张表都按 {@code user_id} 分片（同键同算法 → 同片），
 * 但<b>领域分离</b>——{@code account_credit} 管「软声誉」（信用分/等级/流量池），
 * 本表管「硬执行」（封禁/警告，直接阻断写能力）。二者不互相引用，避免把封禁字段塞进信用表。</p>
 *
 * <p><b>末尾语义</b>：{@code ban_until = NULL} 表示永久封禁；临时封禁到期不自动改状态，
 * 由 {@code PenaltyService#canWrite} 读取时按时点推导（与 P0-5 加严窗口同手法：读取时推导优先，
 * 定时任务只做持久态清理）。</p>
 */
@Repository
public class AccountPenaltyRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入。
     *
     * <p>⚠️ <b>刻意不用 {@code @RequiredArgsConstructor}</b>：本仓库在本机构建环境下存在
     * Lombok 注解处理对「新加入的文件」不生效的情况（表现为 final 字段报“未在默认构造器中初始化”，
     * 同一现象在 {@code PenaltyService} 上表现为 {@code log} 找不到符号）。
     * 为保证可编译性，本包内依赖注入与日志一律显式声明，不依赖 Lombok 代码生成。</p>
     */
    public AccountPenaltyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 确保处罚行存在（不存在则插入 NORMAL / 累计 0）；幂等，已存在则不动（保护存量封禁态）。 */
    public void ensure(long userId) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO account_penalty (user_id, status, violation_count, updated_at) "
                        + "VALUES (?, 0, 0, ?)",
                userId, Timestamp.from(Instant.now()));
    }

    /** 读取当前处罚态（无记录返回 {@link Optional#empty()}，由调用方按 NORMAL 处理）。 */
    public Optional<AccountPenalty> get(long userId) {
        return jdbcTemplate.query(
                "SELECT user_id, status, ban_category, ban_reason, ban_until, violation_count, "
                        + "first_violation_at, last_violation_at, updated_at "
                        + "FROM account_penalty WHERE user_id = ?",
                (ResultSet rs, int rn) -> new AccountPenalty(
                        rs.getLong("user_id"),
                        AccountPenaltyStatus.fromCode(rs.getInt("status")),
                        rs.getObject("ban_category") == null
                                ? null
                                : ViolationCategory.fromCode(rs.getInt("ban_category")),
                        rs.getString("ban_reason"),
                        rs.getTimestamp("ban_until") == null
                                ? null
                                : rs.getTimestamp("ban_until").toInstant(),
                        rs.getInt("violation_count"),
                        rs.getTimestamp("first_violation_at") == null
                                ? null
                                : rs.getTimestamp("first_violation_at").toInstant(),
                        rs.getTimestamp("last_violation_at") == null
                                ? null
                                : rs.getTimestamp("last_violation_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                userId).stream().findFirst();
    }

    /**
     * 落地升级结果（upsert）：写入新状态 / 封禁字段 / 累计计数。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而非「先查后更」，避免并发下两次违规互相覆盖计数。</p>
     */
    public void apply(long userId, AccountPenalty p) {
        jdbcTemplate.update(
                "INSERT INTO account_penalty (user_id, status, ban_category, ban_reason, ban_until, "
                        + "violation_count, first_violation_at, last_violation_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), "
                        + "ban_category = VALUES(ban_category), ban_reason = VALUES(ban_reason), "
                        + "ban_until = VALUES(ban_until), violation_count = VALUES(violation_count), "
                        + "first_violation_at = VALUES(first_violation_at), "
                        + "last_violation_at = VALUES(last_violation_at), updated_at = VALUES(updated_at)",
                userId,
                p.status().code(),
                p.banCategory() == null ? null : p.banCategory().code(),
                p.banReason(),
                p.banUntil() == null ? null : Timestamp.from(p.banUntil()),
                p.violationCount(),
                p.firstViolationAt() == null ? null : Timestamp.from(p.firstViolationAt()),
                p.lastViolationAt() == null ? null : Timestamp.from(p.lastViolationAt()),
                Timestamp.from(Instant.now()));
    }
}
