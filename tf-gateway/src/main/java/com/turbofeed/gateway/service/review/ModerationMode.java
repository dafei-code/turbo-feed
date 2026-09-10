package com.turbofeed.gateway.service.review;

/**
 * 机审策略枚举（配置驱动，决定激活哪个 {@link ContentModeration} 实现）。
 *
 * <p>这是机审「可插拔」的核心开关：新增一种机审能力（如云内容安全 API）只需
 * 新增一个 {@code ContentModeration} 实现并 {@code @Override mode()} 返回对应枚举，
 * 再在 {@code application.yml} 把 {@code turbofeed.media.review.moderation-mode} 指过去即可，
 * 审核主流程（{@code MediaReviewService}）零改动。</p>
 */
public enum ModerationMode {

    /** 占位桩（AutoPassModeration）：恒返回 APPROVED，仅演示/本地联调。 */
    PASS,

    /** 本地视觉模型（AiContentModeration，Ollama qwen2.5-vl）：看图审核，失败安全降级待人审。 */
    AI,

    /** 本地规则引擎（RuleBasedModeration）：文件名/元数据级 fail-closed 拦硬伤，非 AI 视觉；MVP 机审初筛默认。 */
    RULE,

    /** 云内容安全 API（预留 drop-in）：阿里云内容安全 / 腾讯天御 / AWS Rekognition，视觉语义级机审；需用户提供 API key 后接入。 */
    CLOUD
}
