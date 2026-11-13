package com.turbofeed.gateway.service.moderation;

import java.time.Instant;

/**
 * 内容安全命中审计记录（V3，反馈闭环数据底座）。
 *
 * <p>每次 BLOCK/REVIEW/LIMIT 命中写一行。PASS 不写（无命中即无样本）。
 * 人审裁决后由 {@link #withReviewerResult} 回填结论，形成闭环：
 * <ul>
 *   <li>REJECTED（误杀）→ 候选白名单，运营一键确认后进 sensitive_whitelist。</li>
 *   <li>CONFIRMED（确认违规但规则未拦/边界）→ 候选坏样本库，供语义层召回。</li>
 * </ul>
 *
 * <p>字段刻意精简：不存原文全量，只存命中区间打码后的短片段（{@code snippetMasked}），
 * 既够运营核对高亮，又避免敏感词/原文在审计表里二次扩散。</p>
 */
public record ModerationHit(
        long id,
        ContentScene scene,
        long userId,
        String targetId,
        String snippetMasked,
        String matchedWord,
        String category,
        ModerationAction action,
        Long reviewTaskId,
        String reviewerResult,
        Instant createdAt,
        Instant reviewedAt) {

    /** 人审结论常量。 */
    public static final String REVIEWER_CONFIRMED = "CONFIRMED";
    public static final String REVIEWER_REJECTED = "REJECTED";

    /** 人审后回填结论的副本。 */
    public ModerationHit withReviewerResult(String result, Instant at) {
        return new ModerationHit(id, scene, userId, targetId, snippetMasked, matchedWord, category,
                action, reviewTaskId, result, createdAt, at);
    }
}
