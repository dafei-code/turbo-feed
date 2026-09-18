package com.turbofeed.gateway.service.review.credit;

import com.turbofeed.gateway.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * 信用分自然恢复任务（P0-5 增强）：每日凌晨给「近 {@code creditRecoverQuietDays} 天无违规」的
 * 账号缓慢加分（{@code creditRecoverStep}，封顶 100），使其 {@link CreditLevel} 随良好行为自然回升。
 *
 * <p><b>与「加严时间窗」的关系</b>：加严是否仍生效由 {@link AccountCreditRepository#getLevel} 的
 * 时间窗推导（读取时）保证，本任务只负责把信用分<b>逐步恢复</b>，让 {@code level} 真正回升到 L1/L2——
 * 二者正交：窗口到期解除的是「加严标记」，分数恢复才决定能否回到高信用池。</p>
 *
 * <p><b>分片友好</b>：UPDATE 不带分片键 → ShardingSphere 广播到 4 张物理表（{@code account_credit_0..3}），
 * 天然全量覆盖；每日一次、低频，不影响在线写。</p>
 *
 * <p><b>可关</b>：注释掉 {@link Scheduled} 或调大 {@code cron} 即可停用，不影响读取推导的正确性。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditRecoveryTask {

    private final JdbcTemplate jdbcTemplate;
    private final MediaProperties mediaProperties;

    @Scheduled(cron = "0 0 3 * * ?")
    public void recover() {
        MediaProperties.Review review = mediaProperties.getReview();
        Timestamp quietCutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(review.getCreditRecoverQuietDays())));
        // 注意：不过滤 strict_queue_flag=0——该标记持久化后只置 1 不清 0（是否仍加严由读取时时间窗推导），
        // 若按 flag=0 过滤，超窗口的违规账号会被永久排除在恢复外。此处按 last_violation_at 窗口判断，
        // 并在同条 UPDATE 内：① 加分（封顶 100）② 按新分重算 level（避免 level 列停在违规时的 0）
        // ③ 顺手清掉已过期 flag，使持久态自洽。new_user_watch=0：新号分数本就 100，无需恢复。
        int rows = jdbcTemplate.update(
                "UPDATE account_credit SET "
                        + "credit_score = LEAST(100, credit_score + ?), "
                        + "level = CASE WHEN LEAST(100, credit_score + ?) < 60 THEN 0 "
                        + "             ELSE CASE WHEN LEAST(100, credit_score + ?) < 80 THEN 1 ELSE 2 END END, "
                        + "strict_queue_flag = 0, updated_at = NOW() "
                        + "WHERE new_user_watch = 0 AND credit_score < 100 "
                        + "AND (last_violation_at IS NULL OR last_violation_at < ?)",
                review.getCreditRecoverStep(), review.getCreditRecoverStep(), review.getCreditRecoverStep(), quietCutoff);
        log.info("信用分自然恢复（近 {} 天无违规）: step=+{}, affected={}",
                review.getCreditRecoverQuietDays(), review.getCreditRecoverStep(), rows);
    }
}
