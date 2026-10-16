package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.health.AccountHealth;
import com.turbofeed.gateway.service.review.credit.AccountCreditService;
import com.turbofeed.gateway.service.review.credit.CreditLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上传三档风险机审评分器（#8）。
 *
 * <p><b>定位</b>：与「内容是否命中敏感词」解耦——即使内容合规，低健康分 / 低信用 / 高历史违规的账号
 * 仍可能产出高风险内容，应在发布前送人工复审（抖音式「信用是风险底盘」）。本评分器把账号信号
 * 综合成 [0,1] 归一风险分，按外置阈值落 LOW / MED / HIGH 三档。</p>
 *
 * <h3>信号与权重（v1，权重内部常量、阈值外置）</h3>
 * <ul>
 *   <li>账号健康分 riskHealth = (100 - score) / 100 → 权重 0.35；</li>
 *   <li>信用等级 riskCredit：L0=0.8（先审后放/低信用）/ L1=0.3 / L2=0.1 → 权重 0.30；</li>
 *   <li>历史违规数 riskViolation = min(violationCount / 5, 1) → 权重 0.20；</li>
 *   <li>图片分类置信 riskContent = 1 - imageConfidence（缺省 0.5 即中性）→ 权重 0.15。</li>
 * </ul>
 * 风险分 = Σ(权重 × 信号)。阈值：&lt; riskLowThreshold(0.3)=LOW；[low, med)=MED；&gt;= med(0.7)=HIGH。
 *
 * <h3>失败方向</h3>
 * <p>读账号信号（健康分/信用）抛异常时，评分器自身不吞——由调用方 {@code route} 捕获并默认按 MED 路由
 * （保守：宁可多送审、不可漏放），整体 fail-open 不阻断上传。</p>
 */
@Service
public class RiskScorer {

    private static final Logger log = LoggerFactory.getLogger(RiskScorer.class);

    /** v1 各信号权重（和=1.0），调参无需发版只需改此处并重启；阈值仍走配置外置。 */
    static final double W_HEALTH = 0.35;
    static final double W_CREDIT = 0.30;
    static final double W_VIOLATION = 0.20;
    static final double W_CONTENT = 0.15;

    private final MediaProperties mediaProperties;
    private final AccountCreditService accountCreditService;

    public RiskScorer(MediaProperties mediaProperties, AccountCreditService accountCreditService) {
        this.mediaProperties = mediaProperties;
        this.accountCreditService = accountCreditService;
    }

    /** 风险档位。 */
    public enum RiskLevel {
        /** 低风险：放行，不进复审。 */
        LOW,
        /** 中风险：进人工复审队列。 */
        MED,
        /** 高风险：进人工复审队列（更优先）。 */
        HIGH
    }

    /** 评分结果。 */
    public record RiskResult(RiskLevel level, double score) {
    }

    /**
     * 综合账号信号算风险分并落档。
     *
     * @param userId          上传者
     * @param caption         文案（v1 仅占位，内容合规信号未接入；预留扩展）
     * @param health          账号健康分快照（score / violationCount）
     * @param imageConfidence 图片分类置信 [0,1]，null 视为中性 0.5
     * @return 风险档位与原始分
     */
    public RiskResult score(long userId, String caption, AccountHealth health, Double imageConfidence) {
        CreditLevel credit = accountCreditService.getLevel(userId);
        double riskHealth = (100 - clampScore(health.score())) / 100.0;
        double riskCredit = switch (credit) {
            case L0 -> 0.8;
            case L1 -> 0.3;
            case L2 -> 0.1;
        };
        double riskViolation = Math.min(health.violationCount() / 5.0, 1.0);
        double riskContent = 1.0 - (imageConfidence == null ? 0.5 : clamp01(imageConfidence));

        double score = W_HEALTH * riskHealth
                + W_CREDIT * riskCredit
                + W_VIOLATION * riskViolation
                + W_CONTENT * riskContent;
        score = clamp01(score);

        MediaProperties.Review r = mediaProperties.getReview();
        RiskLevel level = score >= r.getRiskMedThreshold() ? RiskLevel.HIGH
                : score >= r.getRiskLowThreshold() ? RiskLevel.MED
                : RiskLevel.LOW;
        log.info("上传风险评分: userId={}, credit={}, health={}, violation={}, imgConf={}, score={}, level={}",
                userId, credit, health.score(), health.violationCount(), imageConfidence, score, level);
        return new RiskResult(level, score);
    }

    private static int clampScore(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
