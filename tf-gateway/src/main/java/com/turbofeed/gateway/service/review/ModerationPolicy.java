package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.service.penalty.ViolationCategory;

/**
 * 双严格度策略解析（feature-match M3）。
 *
 * <p>按 (来源 source, 场景 scenario, 类目 category) 查表得到 {@link ModerationStrictness}，
 * 返回对应 {@link StrictnessProfile}（含自动放行阈值）。</p>
 *
 * <p><b>fail-open</b>：配置缺失/解析异常一律回落 {@code STANDARD}（=全局 {@code confidence-threshold}），
 * 绝不让「未配置严格度」退化为「静默放行公域」。</p>
 */
public interface ModerationPolicy {

    /**
     * 解析某次机审裁定应采用的严格度阈值档案。
     *
     * @param source   机审来源（RULE 文本 / AI / CLOUD 视觉），即当前激活的 {@code ModerationMode}
     * @param scenario 内容分发场景（公域/私域）
     * @param category 违规类目（举报/处置路径有值；上传初筛阶段为 {@code null}）
     * @return 严格度阈值档案（必非 null，缺失时回落 STANDARD）
     */
    StrictnessProfile resolve(ModerationMode source, ModerationScenario scenario, ViolationCategory category);
}
