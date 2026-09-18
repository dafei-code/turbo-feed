package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.review.credit.AccountCredit;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 账号信用仓储（account_credit 表）。
 *
 * <p><b>存储形态</b>：{@code account_credit} 是 ShardingSphere {@code autoTables} 逻辑表，
 * 分片键 {@code user_id}（与 user / media 同键同算法），物理上是 4 张表——
 * ds_0 → {@code account_credit_0/_2}、ds_1 → {@code account_credit_1/_3}。
 * 物理表按「全局连续编号」命名（不是每库各 {@code _0/_1}），加列/建表须逐物理表执行。</p>
 *
 * <p><b>信用分模型</b>（MVP 简化，可后续细化）：满分 100；新注册账号默认 100 分 / L1，
 * 但<b>初始处于新人观察期</b>（{@code new_user_watch=1} → 按 L0 口径先审后放），
 * 人审通过 N 帖后转正常分级。违规确认（举报成立 / 人审驳回）→ 扣 20，
 * 低于 60 降 L0、低于 80 维持 L1、≥80 升 L2；申诉翻案 → 加 10（封顶 100）。</p>
 *
 * <p><b>两条加严路径刻意隔离</b>：{@code strict_queue_flag}（违规加严）与
 * {@code new_user_watch}（新人观察期）语义不同，<b>不复用同一列</b>——否则「新人转正」
 * 会顺手把被处罚账号一并解封。{@link #getLevel} 只要任一为真即按 L0 口径处理。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AccountCreditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MediaProperties mediaProperties;

    /**
     * 确保信用行存在（不存在则插入：100 分 / L1，且<b>进入新人观察期</b>）；幂等。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 只刷新 {@code updated_at}：存量账号的等级与加严标记
     * 不受影响，避免本策略上线时把历史账号整体打回「先审后放」。</p>
     */
    public void ensure(long userId) {
        jdbcTemplate.update(
                "INSERT INTO account_credit (user_id, credit_score, level, strict_queue_flag, "
                        + "new_user_watch, new_user_approved_count, watch_since, updated_at) "
                        + "VALUES (?, 100, 1, 0, 1, 0, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)",
                userId, java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
    }

    /** 读取信用等级（无记录默认 L1，等同 ensure 后的状态）。加严/观察按时间窗推导（P0-5/P0-6）。 */
    public CreditLevel getLevel(long userId) {
        MediaProperties.Review review = mediaProperties.getReview();
        List<Integer> codes = jdbcTemplate.query(
                "SELECT level FROM account_credit WHERE user_id = ?",
                (rs, rn) -> rs.getInt("level"), userId);
        if (codes.isEmpty()) {
            return CreditLevel.L1;
        }
        CreditLevel base = CreditLevel.fromCode(codes.get(0));
        return isStrict(userId, review.getStrictQueueWindowDays(), review.getNewUserWatchWindowDays(),
                review.getNewUserApproveThreshold()) ? CreditLevel.L0 : base;
    }

    /**
     * 是否处于「L0 口径」的加严状态（P0-5/P0-6 改造为时间窗推导）：
     * <b>违规加严</b>（{@code strict_queue_flag=1} 且 {@code last_violation_at} 未超窗口）
     * 或 <b>新人观察期</b>（{@code new_user_watch=1} 且 {@code new_user_approved_count} 未达阈值
     * 且 {@code watch_since} 未超窗口）任一命中即为真。超窗口立即不再加严，杜绝永久加严。
     *
     * <p>cutoff 在 Java 端用 {@code Instant.minus(Duration)} 算好传参，避开 ShardingSphere 对
     * {@code NOW() - INTERVAL ? DAY} 的方言差异；NULL 的 {@code last_violation_at} 视为已过期。</p>
     */
    private boolean isStrict(long userId, int strictWindowDays, int watchWindowDays, int approvedThreshold) {
        java.sql.Timestamp strictCutoff = java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(strictWindowDays)));
        java.sql.Timestamp watchCutoff = java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(watchWindowDays)));
        List<Boolean> flags = jdbcTemplate.query(
                "SELECT ( (strict_queue_flag = 1 AND (last_violation_at IS NULL OR last_violation_at > ?)) "
                        + "OR (new_user_watch = 1 AND (new_user_approved_count >= ? OR watch_since > ?)) ) AS strict "
                        + "FROM account_credit WHERE user_id = ?",
                (rs, rn) -> rs.getBoolean("strict"), strictCutoff, approvedThreshold, watchCutoff, userId);
        return !flags.isEmpty() && Boolean.TRUE.equals(flags.get(0));
    }

    /**
     * 人审通过一帖：累加观察期计数，达到阈值即解除新人观察期（之后按分数正常分级）。
     *
     * <p><b>只应由人工审核路径调用</b>（{@code MediaReviewService#reviewByMediaId}）：
     * 若把先发后审的自动通过也计数，新号第一帖上传后就会把自己顶出观察期，观察期形同虚设。</p>
     *
     * <p><b>违规加严不会被本方法解除</b>（{@code strict_queue_flag} 不动）：被处罚账号需要
     * 独立的申诉/时间窗解除策略。</p>
     *
     * <p><b>SQL 赋值顺序说明</b>：MySQL 单表 UPDATE 的赋值<b>从左到右求值，后面的赋值能看到
     * 前面已改的新值</b>（实测：{@code SET a = a+1, b = a} 得到 {@code b = a+1}）。
     * 因此这里把依赖「旧计数」的 {@code new_user_watch} 放在前面，计数自增放后面——
     * 反过来写会导致阈值判断用上自增后的值（差 1 帖）。</p>
     *
     * @return 本次调用是否解除了新人观察期
     */
    public boolean onHumanApproved(long userId, int threshold) {
        int rows = jdbcTemplate.update(
                "UPDATE account_credit SET "
                        + "new_user_watch = CASE WHEN new_user_approved_count + 1 >= ? THEN 0 ELSE new_user_watch END, "
                        + "new_user_approved_count = new_user_approved_count + 1, "
                        + "updated_at = ? WHERE user_id = ?",
                threshold, java.sql.Timestamp.from(Instant.now()), userId);
        if (rows == 0) {
            return false;
        }
        Integer watching = jdbcTemplate.queryForObject(
                "SELECT new_user_watch FROM account_credit WHERE user_id = ?", Integer.class, userId);
        return watching != null && watching == 0;
    }

    /**
     * 违规确认：扣分并据分重算等级；并置 {@code strict_queue_flag=1} 且写
     * {@code last_violation_at=NOW()}。加严是否仍生效改由 {@link #isStrict} 按
     * {@code strictQueueWindowDays} 时间窗推导（P0-5 已落地），超窗口自动降级，不再永久加严。
     *
     * <p>⚠️ <b>等级滞后已修正（changelog 0029）</b>：原实现先改 {@code credit_score} 再算等级，
     * 因 MySQL UPDATE 赋值从左到右求值，level 里的 {@code credit_score - 20} 看到的是已扣分后的分
     * （等价于扣 40 判级），100 → 80 被算成 L1（应为 L2）。现把 level CASE 前置，引用原始分，判级正确。
     * {@code restore()} 同类修正。</p>
     */
    public void deduct(long userId) {
        // 修正等级滞后：level CASE 必须放在 credit_score 赋值之前（见类注释 SQL 赋值顺序说明）。
        // CASE 前置后引用「未改动前的原始分」，按真实扣分(20)判级；若放其后会看到已扣分，
        // 等价于多扣一档。
        // P0-5：置 strict_queue_flag=1 且写 last_violation_at=NOW()；加严是否仍生效改由 isStrict
        // 按 strictQueueWindowDays 时间窗推导，超窗口自动降级，不再永久加严。
        jdbcTemplate.update(
                "UPDATE account_credit SET "
                        + "level = CASE WHEN credit_score - 20 < 60 THEN 0 "
                        + "             ELSE CASE WHEN credit_score - 20 < 80 THEN 1 ELSE 2 END END, "
                        + "credit_score = GREATEST(0, credit_score - 20), "
                        + "strict_queue_flag = 1, last_violation_at = NOW(), updated_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now()), userId);
    }

    /**
     * 申诉翻案：加分（封顶 100）并据分重算等级。
     *
     * <p>注意：<b>不修改</b> {@code strict_queue_flag}——加严解除改由时间窗推导（P0-5），
     * 翻案只恢复分数，是否仍加严由 {@code last_violation_at} 窗口决定。
     * 与 {@link #deduct} 同类的赋值顺序缺陷已一并修正（level CASE 前置）。</p>
     */
    public void restore(long userId) {
        // 修正等级滞后：level CASE 前置，引用「未改动前的原始分 + 10」判级。
        jdbcTemplate.update(
                "UPDATE account_credit SET "
                        + "level = CASE WHEN LEAST(100, credit_score + 10) < 60 THEN 0 "
                        + "             ELSE CASE WHEN LEAST(100, credit_score + 10) < 80 THEN 1 ELSE 2 END END, "
                        + "credit_score = LEAST(100, credit_score + 10), "
                        + "updated_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now()), userId);
    }

    /** 全量读取（仅调试/巡检用）。 */
    public List<AccountCredit> findAll() {
        return jdbcTemplate.query(
                "SELECT user_id, credit_score, level, strict_queue_flag, new_user_watch, "
                        + "new_user_approved_count, updated_at FROM account_credit",
                (ResultSet rs, int rn) -> new AccountCredit(
                        rs.getLong("user_id"),
                        rs.getInt("credit_score"),
                        CreditLevel.fromCode(rs.getInt("level")),
                        rs.getInt("strict_queue_flag") == 1,
                        rs.getInt("new_user_watch") == 1,
                        rs.getInt("new_user_approved_count"),
                        rs.getTimestamp("updated_at").toInstant()));
    }
}
