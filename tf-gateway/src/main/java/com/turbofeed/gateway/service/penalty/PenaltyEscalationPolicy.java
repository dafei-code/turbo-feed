package com.turbofeed.gateway.service.penalty;

import com.turbofeed.gateway.config.MediaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 处罚升级策略（<b>配置驱动</b>，骨架版）。
 *
 * <p>输入一次违规的「类目 / 严重度」与账号「当前累计违规数 / 当前状态」，输出应施加的
 * {@link PenaltyAction} 与新 {@link AccountPenaltyStatus}。当前规则：</p>
 * <ol>
 *   <li>{@link ViolationSeverity#CRITICAL} → 直接封禁（{@code criticalAutoPermBan=true} 永久封，否则临时封）；</li>
 *   <li>累计违规数 ≥ {@code banTempThreshold} → 临时封禁 {@code banTempDays} 天；</li>
 *   <li>其余 → 警告 {@link PenaltyAction#WARN}（软性，<b>不阻断写</b>）。</li>
 * </ol>
 *
 * <p><b>为何单独成类</b>：升级规则是「最易变」的一块（业务会不断加类目、调阈值、加灰度），
 * 与仓储/服务分离后可独立替换成规则表 / 脚本引擎 / 远端策略服务，不动调用方。</p>
 *
 * <p>⚠️ <b>骨架简化</b>：本版用「累计总数」判断。抖音式「<b>同类</b> N 次才升级」需要按类目计数——
 * 届时把 {@code account_penalty.violation_count} 换成 JSON 类目计数（如 {@code {"CONTENT_PORN":2}}），
 * 本类读取对应类目计数即可，其余逻辑不变。</p>
 */
@Component
public class PenaltyEscalationPolicy {

    private final MediaProperties mediaProperties;

    /** 构造器注入（不用 Lombok，原因同 {@code AccountPenaltyRepository}）。 */
    public PenaltyEscalationPolicy(MediaProperties mediaProperties) {
        this.mediaProperties = mediaProperties;
    }

    /** 一次升级评估的结果。 */
    public record Decision(PenaltyAction action,
                           AccountPenaltyStatus newStatus,
                           ViolationCategory banCategory,
                           String banReason,
                           Instant banUntil) {
    }

    /**
     * 评估应施加的处置。
     *
     * @param category              本次违规类目
     * @param severity              本次严重度
     * @param currentViolationCount 累加本次后的<b>累计违规数</b>（由调用方 +1 后传入）
     * @param currentStatus         当前处罚态（骨架规则未使用，预留给「已封禁不再降级」等后续规则）
     */
    public Decision evaluate(ViolationCategory category,
                             ViolationSeverity severity,
                             int currentViolationCount,
                             AccountPenaltyStatus currentStatus) {
        MediaProperties.Review review = mediaProperties.getReview();

        if (severity == ViolationSeverity.CRITICAL) {
            boolean perm = review.isCriticalAutoPermBan();
            Instant until = perm ? null : Instant.now().plus(Duration.ofDays(review.getBanTempDays()));
            return new Decision(
                    perm ? PenaltyAction.BAN_PERM : PenaltyAction.BAN_TEMP,
                    perm ? AccountPenaltyStatus.BANNED_PERM : AccountPenaltyStatus.BANNED_TEMP,
                    category,
                    "严重违规(CRITICAL): " + category,
                    until);
        }

        if (currentViolationCount >= review.getBanTempThreshold()) {
            Instant until = Instant.now().plus(Duration.ofDays(review.getBanTempDays()));
            return new Decision(
                    PenaltyAction.BAN_TEMP,
                    AccountPenaltyStatus.BANNED_TEMP,
                    category,
                    "累计违规达阈值(" + currentViolationCount + "≥" + review.getBanTempThreshold() + "): " + category,
                    until);
        }

        // 未达封禁阈值 → 软性警告（不阻断写，仅留痕供运营观察）
        return new Decision(PenaltyAction.WARN, AccountPenaltyStatus.WARN,
                category, "违规警告: " + category, null);
    }
}
