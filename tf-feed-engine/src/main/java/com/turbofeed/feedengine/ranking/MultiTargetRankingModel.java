package com.turbofeed.feedengine.ranking;

import org.springframework.stereotype.Component;

/**
 * 多目标联合预估 + 融合精排（抖音式「精排模型化」G4：用多目标替代单一线性目标）。
 *
 * <p><b>对标的是什么</b>：线性模型把「完播率 + 互动率 + 兴趣」挤进一个目标，
 * 无法平衡「互动 vs 时长 vs 留存」之间的权衡。抖音精排是<b>多目标联合预估</b>
 * （CTR / CVR / 时长 / 互动各一个塔），再按业务权重融合。本实现把四个目标显式拆出来，
 * 各自先归一化到 {@code [0,1]} 附近，再按可配权重融合——权重就是「业务更看重什么」的旋钮。</p>
 *
 * <p>四个目标：
 * <ul>
 *   <li><b>CTR</b> ≈ 互动率（点不点）：likes+comments+shares / impressions；</li>
 *   <li><b>CVR</b> ≈ 完播率（看完没看完）：playCompletes / impressions；</li>
 *   <li><b>dwell</b> ≈ 新鲜度代理「停留意愿」：入流越近越靠前（72 窗口≈3 天归一到 [0,1]）；</li>
 *   <li><b>interact</b> ≈ 绝对互动强度（非比率，防小曝光内容被先验压没）。</li>
 * </ul>
 * 融合分经 {@code recencyWindowMillis} 量纲统一后叠加到 recency 上，再乘作者健康分系数，
 * 与线性 / DeepFM 模型同量纲、可平滑切换。</p>
 *
 * <p><b>生产缝</b>：此处是确定性融合（MMOE 占位）。真实多目标模型（如 MMOE / PLE）的塔输出
 * 通过 {@link RankModelClient} 接入即可替换融合项的来源，读路径不变。</p>
 */
@Component
public class MultiTargetRankingModel implements RankingModel {

    private final RankingProperties props;

    public MultiTargetRankingModel(RankingProperties props) {
        this.props = props;
    }

    @Override
    public double score(RankingFeatures f) {
        double window = props.getRecencyWindowMillis();
        double completion = smooth(f.playCompletes(), f.impressions(),
                props.getCompletionPriorCount(), props.getCompletionPriorRate());
        long interactions = f.likes() + f.comments() + f.shares();
        double interaction = smooth(interactions, f.impressions(),
                props.getInteractionPriorCount(), props.getInteractionPriorRate());

        // 四个目标各自归一到 [0,1] 附近
        double ctr = interaction;                              // 互动率 ≈ CTR
        double cvr = completion;                              // 完播率 ≈ CVR
        double dwell = clamp01(f.recencyMillis() / (window * 72.0)); // 新鲜度代理停留意愿
        double interact = clamp01(interaction * 4.0);         // 绝对互动强度（非比率）

        // 多目标融合（权重是「业务更看重什么」的旋钮，可配）
        double fused = props.getMtCtrWeight() * ctr
                + props.getMtCvrWeight() * cvr
                + props.getMtDwellWeight() * dwell
                + props.getMtInteractWeight() * interact;

        double base = f.recencyMillis()
                + window * fused
                - (f.negativeHit() ? props.getNegativePenaltyMillis() : 0.0d);
        // 作者健康分排序系数（与线性 / DeepFM 同接入点、同 fail-open）
        return base * f.authorHealthScale();
    }

    private static double clamp01(double x) {
        return x < 0d ? 0d : (x > 1d ? 1d : x);
    }

    /** 贝叶斯平滑（与线性模型同实现）。 */
    public double smooth(long successes, long trials, double priorCount, double priorRate) {
        if (priorCount <= 0.0d) {
            return trials <= 0 ? 0.0d : (double) successes / trials;
        }
        return (successes + priorCount * priorRate) / (trials + priorCount);
    }
}
