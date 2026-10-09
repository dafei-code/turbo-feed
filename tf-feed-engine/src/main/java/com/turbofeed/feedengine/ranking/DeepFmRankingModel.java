package com.turbofeed.feedengine.ranking;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 字段感知 FM 精排（抖音式「精排模型化」G3：在线性项之外显式做特征交叉）。
 *
 * <p><b>对标的是什么</b>：线性加权只能表达「各目标独立相加」，表达不了
 * 「兴趣 ∩ 短期」「兴趣 ∩ 实时」「完播 ∩ 互动」这类<b>特征交叉</b>非线性信号——
 * 而抖音的 DeepFM 正是靠 embedding 化的特征交叉捕捉「又新鲜、又对你的短期胃口、又高完播」的联合效应。
 * 本实现用<b>确定性伪随机嵌入</b>把每个特征域 hash 成固定维向量，做标准 FM 二阶交叉项，
 * 不依赖任何离线训练产物也能跑（生产缝在 {@link RankModelClient}：接真实 DeepFM serving 即替换交叉项的来源）。</p>
 *
 * <p><b>与线性模型同量纲、零回归</b>：线性项完全复用 {@link RankingProperties} 的同套目标权重，
 * 交叉项默认权重很小（{@code deepfm-cross-weight}），且交叉权重为 0 时本模型 ≡ 线性模型。
 * 因此切到 {@code DEEPFM} 不会让排序量级突变，只在其上叠加「交叉增强」。</p>
 *
 * <p><b>fail-open</b>：远程模型（{@link RankModelClient}）异常 / 未配置 → 退回本地 FM，不抛。</p>
 */
@Component
public class DeepFmRankingModel implements RankingModel {

    private final RankingProperties props;
    private final ObjectProvider<RankModelClient> remoteClient;

    public DeepFmRankingModel(RankingProperties props, ObjectProvider<RankModelClient> remoteClient) {
        this.props = props;
        this.remoteClient = remoteClient;
    }

    @Override
    public double score(RankingFeatures f) {
        // 生产缝：开启远程且存在 RankModelClient bean → 直发远程 serving（语义一致：越大越靠前）。
        if (props.isRemoteModelEnabled()) {
            RankModelClient client = remoteClient.getIfAvailable();
            if (client != null) {
                try {
                    return client.predict(f);
                } catch (Exception e) {
                    // 远程不可用：退回本地 FM（fail-open 不阻断浏览）。
                }
            }
        }
        return localFm(f);
    }

    /** 本地确定性 FM：线性项（复用线性模型同套权重）+ 二阶特征交叉项。 */
    private double localFm(RankingFeatures f) {
        double window = props.getRecencyWindowMillis();
        // 与 LinearWeightedRankingModel 同口径的贝叶斯平滑 + 量纲统一
        double completion = smooth(f.playCompletes(), f.impressions(),
                props.getCompletionPriorCount(), props.getCompletionPriorRate());
        long interactions = f.likes() + f.comments() + f.shares();
        double interaction = smooth(interactions, f.impressions(),
                props.getInteractionPriorCount(), props.getInteractionPriorRate());
        double interest = Math.min(f.interestMatch(), props.getInterestScoreCap());
        double shortTerm = Math.min(f.shortTermMatch(), props.getShortTermScoreCap());
        double session = Math.min(f.sessionMatch(), props.getSessionScoreCap());
        double realtime = Math.min(f.realtimeMatch(), props.getRealtimeScoreCap());

        // 线性项：crossWeight=0 时 ≡ 线性模型（零回归保证）
        double linear = f.recencyMillis()
                + window * (props.getCompletionWeight() * completion
                          + props.getInteractionWeight() * interaction
                          + props.getInterestWeight() * interest
                          + props.getShortTermWeight() * shortTerm
                          + props.getSessionWeight() * session
                          + props.getRealtimeWeight() * realtime)
                - (f.negativeHit() ? props.getNegativePenaltyMillis() : 0.0d);

        // 二阶交叉项（FM）：捕捉线性表达不了的特征联合效应
        double cross = crossTerm(interest, shortTerm, session, realtime, completion, interaction);
        double base = linear + window * props.getDeepfmCrossWeight() * cross;
        // 作者健康分排序系数（与线性模型同接入点、同 fail-open）
        return base * f.authorHealthScale();
    }

    /** FM 二阶交叉：Σ_{a<b} <v_a, v_b> · x_a · x_b，嵌入由确定性 hash 初始化。 */
    private double crossTerm(double... x) {
        int dim = props.getDeepfmFieldDim();
        double sum = 0d;
        for (int a = 0; a < x.length; a++) {
            for (int b = a + 1; b < x.length; b++) {
                double dot = 0d;
                for (int j = 0; j < dim; j++) {
                    dot += embed(a, j) * embed(b, j);
                }
                sum += dot * x[a] * x[b];
            }
        }
        return sum;
    }

    /** 确定性伪随机嵌入（固定 seed，不依赖训练产物也能给出稳定交叉信号）。 */
    private double embed(int field, int dim) {
        long h = (long) field * 2654435761L + (long) dim * 40503L + props.getDeepfmSeed();
        return Math.sin(h * 0.0001); // 落在 [-1,1]
    }

    /** 贝叶斯平滑（与 LinearWeightedRankingModel 同实现，便于本地 FM 独立运行）。 */
    public double smooth(long successes, long trials, double priorCount, double priorRate) {
        if (priorCount <= 0.0d) {
            return trials <= 0 ? 0.0d : (double) successes / trials;
        }
        return (successes + priorCount * priorRate) / (trials + priorCount);
    }
}
