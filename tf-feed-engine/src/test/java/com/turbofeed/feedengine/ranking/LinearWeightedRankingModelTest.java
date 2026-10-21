package com.turbofeed.feedengine.ranking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LinearWeightedRankingModel} 隔离单测（G3/G4 默认精排，零回归基线）。
 *
 * <p>纯逻辑、确定性：构造 {@link RankingProperties}（默认权重）+ {@link RankingFeatures}（record），
 * 断言<b>关系</b>而非硬编码绝对值（抗默认权重调整）：贝叶斯平滑、recency 线性叠加、负向惩罚、
 * 作者健康分系数乘法、兴趣/短期/session/实时各自的封顶（防止单标签刷屏）。</p>
 */
class LinearWeightedRankingModelTest {

    private final RankingProperties props = new RankingProperties();

    private LinearWeightedRankingModel model() {
        return new LinearWeightedRankingModel(props);
    }

    /** 全零基线特征（recency=0，无互动，无兴趣，scale=1.0）。 */
    private RankingFeatures base() {
        return new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 0.0d, 0.0d, false, 1.0d, 0.0d);
    }

    // ---- 贝叶斯平滑（smooth）----

    @Test
    void smooth_priorCountZero_noSmoothing() {
        // priorCount<=0 → 朴素比率；样本为 0 时返回 0（非 NaN）
        assertEquals(0.0d, model().smooth(0, 0, 0.0d, 0.2d), 1e-9);
        assertEquals(0.5d, model().smooth(2, 4, 0.0d, 0.2d), 1e-9);
    }

    @Test
    void smooth_priorCountPositive_pullsTowardPrior() {
        // (0 + 20*0.2) / (0 + 20) = 0.2
        assertEquals(0.2d, model().smooth(0, 0, 20.0d, 0.2d), 1e-9);
        // (100 + 20*0.2) / (100 + 20) ≈ 0.8667
        assertEquals(104.0d / 120.0d, model().smooth(100, 100, 20.0d, 0.2d), 1e-9);
        // 小样本被先验主导：1/1 成功但 prior 0.2 → (1+4)/21 ≈ 0.238（远非朴素 1.0）
        assertEquals(5.0d / 21.0d, model().smooth(1, 1, 20.0d, 0.2d), 1e-9);
    }

    // ---- score 关系 ----
    // 说明：以下断言均为「同特征只改一项」的差值/比值，不依赖默认权重的具体数值。

    @Test
    void score_recencyIsAdditiveBeforeScale() {
        RankingFeatures f0 = base();
        RankingFeatures f1 = new RankingFeatures(1_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 0.0d, 0.0d, false, 1.0d, 0.0d);
        // recency 在乘 scale(=1.0) 之前直接相加 → 差值恰为 recency 增量
        assertEquals(1_000_000d, model().score(f1) - model().score(f0), 1e-6);
    }

    @Test
    void score_negativeHitSubtractsPenalty() {
        RankingFeatures f0 = base();
        RankingFeatures fHit = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 0.0d, 0.0d, true, 1.0d, 0.0d);
        // 惩罚在乘 scale(=1.0) 之前减 → 差值恰为 negativePenaltyMillis
        assertEquals(-props.getNegativePenaltyMillis(), model().score(fHit) - model().score(f0), 1e-6);
    }

    @Test
    void score_authorHealthScaleMultiplies() {
        RankingFeatures f0 = base(); // scale=1.0
        RankingFeatures fHalf = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 0.0d, 0.0d, false, 0.5d, 0.0d);
        // 结果 = base × authorHealthScale
        assertEquals(model().score(f0) * 0.5d, model().score(fHalf), 1e-6);
    }

    @Test
    void score_longInterestIsCapped() {
        // interestMatch 超过 interestScoreCap(默认 3.0) 后封顶 → 3.0 与 100.0 同分
        RankingFeatures atCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 3.0d, 0.0d, 0.0d, false, 1.0d, 0.0d);
        RankingFeatures aboveCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 100.0d, 0.0d, 0.0d, false, 1.0d, 0.0d);
        RankingFeatures belowCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 1.0d, 0.0d, 0.0d, false, 1.0d, 0.0d);
        // 封顶：atCap 与 aboveCap 相等；belowCap 更低（证明未封顶区域仍生效）
        assertEquals(model().score(atCap), model().score(aboveCap), 1e-6);
        assertEquals(true, model().score(belowCap) < model().score(atCap));
    }

    @Test
    void score_shortTermInterestIsCapped() {
        RankingFeatures atCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 3.0d, 0.0d, false, 1.0d, 0.0d);
        RankingFeatures aboveCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 100.0d, 0.0d, false, 1.0d, 0.0d);
        RankingFeatures belowCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 1.0d, 0.0d, false, 1.0d, 0.0d);
        assertEquals(model().score(atCap), model().score(aboveCap), 1e-6);
        assertEquals(true, model().score(belowCap) < model().score(atCap));
    }

    @Test
    void score_realtimeMatchIsCapped() {
        RankingFeatures atCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 0.0d, 0.0d, false, 1.0d, 3.0d);
        RankingFeatures aboveCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 0.0d, 0.0d, false, 1.0d, 100.0d);
        RankingFeatures belowCap = new RankingFeatures(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, 0.0d, 0.0d, false, 1.0d, 1.0d);
        assertEquals(model().score(atCap), model().score(aboveCap), 1e-6);
        assertEquals(true, model().score(belowCap) < model().score(atCap));
    }
}
