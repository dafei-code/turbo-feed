package com.turbofeed.feedengine.ranking;

/**
 * 精排模型族选型（抖音式「精排模型化」演进开关）。
 *
 * <p>从「线性加权」走向「深度学习精排」不是一步到位——先让 {@link RankingModel} 这条打分插槽
 * 支持多实现、可配置切换，再逐个补模型。默认 {@link #LINEAR}（与改造前语义完全一致，零回归），
 * 切到 {@link #DEEPFM} / {@link #MULTI_TARGET} 即启用对应的模型化打分器。</p>
 */
public enum RankModelType {
    /** 线性加权多目标（默认，零回归）。 */
    LINEAR,
    /** 字段感知 FM（Field-aware Factorization Machine）：在线性项之外显式做特征交叉。 */
    DEEPFM,
    /** 多目标联合预估 + 融合（CTR/CVR/时长/互动）：替代单一线性目标。 */
    MULTI_TARGET;

    /** 解析配置字符串（容错：非法值回落 LINEAR，fail-open 不阻断）。 */
    public static RankModelType parse(String s) {
        if (s == null) {
            return LINEAR;
        }
        try {
            return RankModelType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LINEAR;
        }
    }
}
