package com.turbofeed.gateway.service.review.credit;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.ReporterCreditRepository;
import com.turbofeed.gateway.service.health.AccountHealthService;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.signal.ModerationSignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 举报人信用服务（P1 #131 编排层）。
 *
 * <p><b>领域边界</b>：只管「举报行为信用」，与账号信用（内容分流）/账号健康分（软处置）/处罚（硬封禁）
 * 各自独立——恶意举报惩罚复用 {@code AccountHealthService} 健康分通道（扣 LOW），但不污染内容违规计数。</p>
 *
 * <p><b>信用语义</b>：初始 100（无罪推定）；举报被管理员/REVIEWER 确认成立 → +upStep（clamp 100），
 * 被驳回 → -downStep（clamp 0）；低信用（&lt; floor）举报人的举报<b>不计入</b>复审升级（{@link #isCredible}），
 * 使惯于恶意举报者无法再刷起人工复审台。</p>
 */
@Service
public class ReporterCreditService {

    /**
     * 显式日志与构造器（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新文件不生效）。
     */
    private static final Logger log = LoggerFactory.getLogger(ReporterCreditService.class);

    private static final int MAX = 100;
    private static final int MIN = 0;

    private final ReporterCreditRepository repository;
    private final MediaProperties mediaProperties;
    private final AccountHealthService accountHealthService;
    /** 审核信号外供 KV（P2-1）：举报人信用写成 Redis，供上游推荐系统消费。 */
    private final ModerationSignalService moderationSignalService;

    public ReporterCreditService(ReporterCreditRepository repository, MediaProperties mediaProperties,
                                 AccountHealthService accountHealthService,
                                 ModerationSignalService moderationSignalService) {
        this.repository = repository;
        this.mediaProperties = mediaProperties;
        this.accountHealthService = accountHealthService;
        this.moderationSignalService = moderationSignalService;
    }

    /** 每次举报提交成功（落库后）调用：累计总举报数 + 刷新最后举报时间。 */
    public void recordReport(long userId) {
        ReporterCredit cur = getOrInit(userId);
        repository.apply(userId, cur.totalReports() + 1, cur.upheld(), cur.rejected(),
                cur.credibility(), Instant.now());
    }

    /** 举报被确认成立 → 信用加分（clamp 0~100）。 */
    public void onReportUpheld(long userId) {
        ReporterCredit cur = getOrInit(userId);
        int next = clamp(cur.credibility() + upStep());
        repository.apply(userId, cur.totalReports(), cur.upheld() + 1, cur.rejected(),
                next, cur.lastReportAt());
        log.info("举报人信用加分（举报成立）: userId={}, credibility={}->{}", userId, cur.credibility(), next);
        // P2-1：把「举报人信用」写成外供 KV（刷新式）。
        moderationSignalService.publishReporterCredit(userId, next, accountHealthService.isBanned(userId));
    }

    /**
     * 举报被驳回 → 信用扣分（clamp 0~100）；累计驳回达阈值额外扣健康分（恶意举报惩罚，复用 P0-b 通道）。
     *
     * <p>健康分惩罚走 {@code AccountHealthService#recordViolation(userId, LOW)}，与内容违规三通道解耦——
     * 恶意举报者的「发帖/推荐/变现」能力被逐级限制，但不计入内容违规计数。</p>
     */
    public void onReportRejected(long userId) {
        ReporterCredit cur = getOrInit(userId);
        int next = clamp(cur.credibility() - downStep());
        int rejected = cur.rejected() + 1;
        repository.apply(userId, cur.totalReports(), cur.upheld(), rejected, next, cur.lastReportAt());
        log.info("举报人信用扣分（举报被驳回）: userId={}, credibility={}->{}", userId, cur.credibility(), next);
        // P2-1：把「举报人信用」写成外供 KV（刷新式）。
        moderationSignalService.publishReporterCredit(userId, next, accountHealthService.isBanned(userId));
        if (rejected >= mediaProperties.getReview().getReporterAbuseRejectedThreshold()) {
            // 恶意举报惩罚：复用账号健康分通道（扣 LOW），与内容违规三通道解耦。
            accountHealthService.recordViolation(userId, ViolationSeverity.LOW);
            log.warn("恶意举报惩罚：累计驳回达阈值，扣健康分: userId={}, rejected={}", userId, rejected);
        }
    }

    /** 信用是否达标：达标（&gt;= floor）的举报人才计入复审升级；无记录按满分 100 处理（无罪推定）。 */
    public boolean isCredible(long userId) {
        return credibilityOf(userId) >= mediaProperties.getReview().getReporterCredibilityFloor();
    }

    public int credibilityOf(long userId) {
        return repository.credibilityOf(userId);
    }

    private ReporterCredit getOrInit(long userId) {
        return repository.get(userId)
                .orElse(new ReporterCredit(userId, 0, 0, 0, MAX, null, Instant.now(), Instant.now()));
    }

    private int upStep() {
        return mediaProperties.getReview().getReporterCredibilityUpStep();
    }

    private int downStep() {
        return mediaProperties.getReview().getReporterCredibilityDownStep();
    }

    private static int clamp(int v) {
        return Math.max(MIN, Math.min(MAX, v));
    }
}
