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
}
