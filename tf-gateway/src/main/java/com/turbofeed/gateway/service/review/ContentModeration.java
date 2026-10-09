package com.turbofeed.gateway.service.review;

/**
 * 内容审核端口（机审能力抽象）。
 *
 * <p>机审「可插拔」设计：每种机审能力是一个实现类，通过 {@link #mode()} 声明自己对应哪种
 * {@link ModerationMode}；{@link ContentModerationRouter} 按配置 {@code moderation-mode}
 * 选策激活其一。新增能力（如云内容安全 API）只需新增实现 + 在枚举补一个值，
 * 审核主流程（{@code MediaReviewService}）零改动。</p>
 *
 * @param mediaId 内容标识
 * @param userId  归属用户（分片键，预留做用户级风控策略）
 * @param url     媒体可访问地址（机审可能需下载原图识别）
 * @return 机审裁定状态（APPROVED / REJECTED）
 */
public interface ContentModeration {

    /** 本实现对应的机审策略；默认 PASS（占位桩），子类按需覆盖。 */
    default ModerationMode mode() {
        return ModerationMode.PASS;
    }

    MediaStatus moderate(String mediaId, long userId, String url);

    /**
     * 带置信度梯度的机审裁定（feature-match M1，moderation-design §P0-1）。
     *
     * <p>默认实现委托 {@link ModerationVerdict#of} 把 {@link #moderate()} 的二值结论包成
     * 「确定通过/驳回」（置信度 1.0、不强制人审），因此未覆盖本方法的既有实现<b>零改动</b>
     * 即获得等价行为；新能力（如云内容安全 API）可覆盖本方法返回真实置信度/标签/人审标记。</p>
     *
     * @see ModerationVerdict
     */
    default ModerationVerdict verdict(String mediaId, long userId, String url) {
        return ModerationVerdict.of(moderate(mediaId, userId, url));
    }
}
