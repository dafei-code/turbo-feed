package com.turbofeed.gateway.service.health;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.repository.AccountHealthRepository;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 账号健康分服务（P0-b 编排层）。
 *
 * <p><b>领域边界</b>：本服务只管「软健康分」，与 {@code PenaltyService}（硬封禁）互不耦合——
 * 一次确认违规由调用方同时触发三条独立通道：{@code AccountCreditService#onViolationConfirmed}（信用）、
 * {@code PenaltyService#recordViolation}（处罚）、以及本服务 {@link #recordViolation}（健康分），
 * 各自落各自表，避免互相污染。</p>
 *
 * <h3>阶梯（对齐抖音「先流量隔离，后硬性封禁」）</h3>
 * <ul>
 *   <li>score &gt;= 80 : 健康，正常推荐与投稿；</li>
 *   <li>60 &lt;= score &lt; 80 : 推荐降权（{@link #recommendationWeight} = 0.5）；</li>
 *   <li>40 &lt;= score &lt; 60 : 限投稿（{@link #canSubmit} = false）；</li>
 *   <li>score &lt; 40 : 限变现（{@link #canMonetize} = false）；</li>
 *   <li>score &lt;= 0 : 封禁（{@link #isBanned} = true，写路径拒绝）。</li>
 * </ul>
 *
 * <p><b>扣分点外部化</b>：{@code turbofeed.media.review.health-deduct-*}（默认
 * CRITICAL=30 / HIGH=20 / MID=10 / LOW=5），调参不发黑。</p>
 */
@Service
public class AccountHealthService {

    /**
     * 显式日志与构造器（不用 {@code @Slf4j} / {@code @RequiredArgsConstructor}：本机构建环境 Lombok 对新建文件不生效）。
     */
    private static final Logger log = LoggerFactory.getLogger(AccountHealthService.class);

    private static final int MAX_SCORE = 100;

    private final AccountHealthRepository repository;
    private final MediaProperties mediaProperties;

    public AccountHealthService(AccountHealthRepository repository, MediaProperties mediaProperties) {
        this.repository = repository;
        this.mediaProperties = mediaProperties;
    }

    /**
     * 确认违规 → 按严重度扣分（clamp 0~100）。MONITOR/INTERCEPT 都走这里（都是已确认违规）。
     *
     * @return 扣分后分数
     */
    public int recordViolation(long userId, ViolationSeverity severity) {
        AccountHealth cur = getOrInit(userId);
        int deduct = deductPoints(severity);
        int next = clamp(cur.score() - deduct);
        repository.apply(userId, next, cur.violationCount() + 1, Instant.now(), null);
        log.warn("账号健康分扣分: userId={}, severity={}, -{}, score={}->{}",
                userId, severity, deduct, cur.score(), next);
        return next;
    }

    /** 良好行为加分恢复（clamp 0~100）。 */
    public int recover(long userId, int points) {
        AccountHealth cur = getOrInit(userId);
        int next = clamp(cur.score() + points);
        repository.apply(userId, next, cur.violationCount(), null, Instant.now());
        log.info("账号健康分恢复: userId={}, +{}, score={}->{}", userId, points, cur.score(), next);
        return next;
    }

    public AccountHealth getHealth(long userId) {
        return getOrInit(userId);
    }

    /** 健康分 &lt; 60 → 限制投稿。 */
    public boolean canSubmit(long userId) {
        return getOrInit(userId).score() >= 60;
    }

    /** 健康分 &lt; 40 → 限制变现。 */
    public boolean canMonetize(long userId) {
        return getOrInit(userId).score() >= 40;
    }

    /** 健康分 &lt;= 0 → 封禁（与 account_penalty 硬封等价，写路径应拒绝）。 */
    public boolean isBanned(long userId) {
        return getOrInit(userId).score() <= 0;
    }

    /** 推荐权重：&gt;=80→1.0，[60,80)→0.5，&lt;60→0.0（抖音式「先流量隔离」）。 */
    public double recommendationWeight(long userId) {
        int s = getOrInit(userId).score();
        if (s >= 80) {
            return 1.0;
        }
        if (s >= 60) {
            return 0.5;
        }
        return 0.0;
    }

    private AccountHealth getOrInit(long userId) {
        return repository.get(userId)
                .orElse(new AccountHealth(userId, MAX_SCORE, 0, null, null, null, Instant.now()));
    }

    private int deductPoints(ViolationSeverity severity) {
        MediaProperties.Review r = mediaProperties.getReview();
        return switch (severity) {
            case CRITICAL -> r.getHealthDeductCritical();
            case HIGH -> r.getHealthDeductHigh();
            case MID -> r.getHealthDeductMid();
            case LOW -> r.getHealthDeductLow();
        };
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(MAX_SCORE, v));
    }
}
