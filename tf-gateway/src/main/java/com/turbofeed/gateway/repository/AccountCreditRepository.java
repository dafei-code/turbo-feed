package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.service.review.credit.AccountCredit;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

/**
 * 账号信用仓储（account_credit 表，单表，落在 ds_0；分片键 user_id 仅用于与 user 同源查询）。
 *
 * <p><b>信用分模型</b>（MVP 简化，可后续细化）：满分 100，新账号默认 L1(100)；
 * 违规确认（举报成立/人审驳回）→ 扣 20，低于 60 降 L0、低于 80 维持 L1、≥80 升 L2；
 * 申诉翻案 → 加 10（封顶 100）。近 30 天有下架 → strictQueue=true（所有内容按 L0 先审后放）。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AccountCreditRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 确保信用行存在（不存在插默认 L1/100）；幂等。 */
    public void ensure(long userId) {
        jdbcTemplate.update(
                "INSERT INTO account_credit (user_id, credit_score, level, strict_queue_flag, updated_at) "
                        + "VALUES (?, 100, 1, 0, ?) "
                        + "ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)",
                userId, java.sql.Timestamp.from(Instant.now()));
    }

    /** 读取信用等级（无记录默认 L1，等同 ensure 后的状态）。 */
    public CreditLevel getLevel(long userId) {
        List<Integer> codes = jdbcTemplate.query(
                "SELECT level FROM account_credit WHERE user_id = ?",
                (rs, rn) -> rs.getInt("level"), userId);
        if (codes.isEmpty()) {
            return CreditLevel.L1;
        }
        boolean strict = isStrict(userId);
        CreditLevel base = CreditLevel.fromCode(codes.get(0));
        return strict ? CreditLevel.L0 : base;
    }

    private boolean isStrict(long userId) {
        List<Integer> flags = jdbcTemplate.query(
                "SELECT strict_queue_flag FROM account_credit WHERE user_id = ?",
                (rs, rn) -> rs.getInt("strict_queue_flag"), userId);
        return !flags.isEmpty() && flags.get(0) == 1;
    }

    /** 违规确认：扣分并据分重算等级；近 30 天有下架置 strict_queue。 */
    public void deduct(long userId) {
        jdbcTemplate.update(
                "UPDATE account_credit SET credit_score = GREATEST(0, credit_score - 20), "
                        + "level = CASE WHEN credit_score - 20 < 60 THEN 0 ELSE CASE WHEN credit_score - 20 < 80 THEN 1 ELSE 2 END END, "
                        + "strict_queue_flag = 1, updated_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now()), userId);
    }

    /** 申诉翻案：加分（封顶 100）并据分重算等级；连续翻案可解除 strict_queue。 */
    public void restore(long userId) {
        jdbcTemplate.update(
                "UPDATE account_credit SET credit_score = LEAST(100, credit_score + 10), "
                        + "level = CASE WHEN LEAST(100, credit_score + 10) < 60 THEN 0 ELSE CASE WHEN LEAST(100, credit_score + 10) < 80 THEN 1 ELSE 2 END END, "
                        + "updated_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now()), userId);
    }

    /** 全量读取（仅调试/巡检用）。 */
    public List<AccountCredit> findAll() {
        return jdbcTemplate.query(
                "SELECT user_id, credit_score, level, strict_queue_flag, updated_at FROM account_credit",
                (ResultSet rs, int rn) -> new AccountCredit(
                        rs.getLong("user_id"),
                        rs.getInt("credit_score"),
                        CreditLevel.fromCode(rs.getInt("level")),
                        rs.getInt("strict_queue_flag") == 1,
                        rs.getTimestamp("updated_at").toInstant()));
    }
}
