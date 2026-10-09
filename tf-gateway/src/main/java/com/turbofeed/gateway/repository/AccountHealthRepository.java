package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.health.AccountHealth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 账号健康分仓储（account_health 单表，ds_0 = turbo_feed_1）。
 *
 * <p>与 {@link AccountPenaltyRepository} 同套路（单表、JdbcTemplate、显式构造器、不依赖 Lombok）。
 * 该表经 ShardingSphere {@code !SINGLE} 托管（见 shardingsphere-config*.yml）。</p>
 */
@Repository
public class AccountHealthRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 显式构造器注入（不用 {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    public AccountHealthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 读取当前健康分（无记录返回 {@link Optional#empty()}，由调用方按满分 100 处理）。 */
    public Optional<AccountHealth> get(long userId) {
        return jdbcTemplate.query(
                "SELECT user_id, score, violation_count, last_deduct_at, last_recover_at, "
                        + "created_at, updated_at FROM account_health WHERE user_id = ?",
                (ResultSet rs, int rn) -> new AccountHealth(
                        rs.getLong("user_id"),
                        rs.getInt("score"),
                        rs.getInt("violation_count"),
                        rs.getTimestamp("last_deduct_at") == null
                                ? null : rs.getTimestamp("last_deduct_at").toInstant(),
                        rs.getTimestamp("last_recover_at") == null
                                ? null : rs.getTimestamp("last_recover_at").toInstant(),
                        rs.getTimestamp("created_at") == null
                                ? null : rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at") == null
                                ? null : rs.getTimestamp("updated_at").toInstant()),
                userId).stream().findFirst();
    }

    /**
     * 落地扣分/加分结果（upsert）：用 {@code ON DUPLICATE KEY UPDATE} 而非「先查后更」，
     * 避免并发下两次违规互相覆盖分数。
     */
    public void apply(long userId, int score, int violationCount,
                      Instant lastDeductAt, Instant lastRecoverAt) {
        jdbcTemplate.update(
                "INSERT INTO account_health (user_id, score, violation_count, "
                        + "last_deduct_at, last_recover_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE score = VALUES(score), "
                        + "violation_count = VALUES(violation_count), "
                        + "last_deduct_at = VALUES(last_deduct_at), "
                        + "last_recover_at = VALUES(last_recover_at), "
                        + "updated_at = VALUES(updated_at)",
                userId,
                score,
                violationCount,
                lastDeductAt == null ? null : Timestamp.from(lastDeductAt),
                lastRecoverAt == null ? null : Timestamp.from(lastRecoverAt),
                Timestamp.from(Instant.now()));
    }
}
