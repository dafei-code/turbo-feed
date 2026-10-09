package com.turbofeed.gateway.service.review;

import java.util.List;

/**
 * 机审裁定（带置信度梯度的二值超集）。
 *
 * <p>{@link ContentModeration#verdict} 的返回类型：把机审从「二值（APPROVED/REJECTED）」升级为
 * 「裁定 + 置信度 + 标签 + 是否强制人审」，二值为其特例（{@code confidence=1.0} 即确定结论）。</p>
 *
 * <p><b>用途</b>：让 {@code MediaReviewService} 能表达「中等把握」——既不放行也不硬拦，而是强制送人审
 * （先审后放），解决二值结构下「放宽漏放 / 收紧误杀」的二选一困境（moderation-design §P0-1）。</p>
 *
 * <p>既有 {@link ContentModeration} 实现只覆盖 {@code moderate()}（二值），通过接口默认方法
 * {@code verdict()} 委托 {@link #of} 自动获得「确定通过/驳回」等价行为，无需逐类改造。</p>
 */
public record ModerationVerdict(
        MediaStatus decision,
        double confidence,
        List<String> labels,
        boolean needHumanScan) {

    /** 二值等价构造：确定结论，置信度 1.0、无标签、不强制人审。供未覆盖 {@code verdict} 的实现/默认委托使用。 */
    public static ModerationVerdict of(MediaStatus decision) {
        return new ModerationVerdict(decision, 1.0, List.of(), false);
    }

    /** 是否「确定通过」：已批准、非强制人审、且高置信度。 */
    public boolean isConfidentPass() {
        return decision == MediaStatus.APPROVED && !needHumanScan && confidence >= 1.0;
    }
}
