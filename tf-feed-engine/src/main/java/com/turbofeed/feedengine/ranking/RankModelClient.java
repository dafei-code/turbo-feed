package com.turbofeed.feedengine.ranking;

/**
 * 远程精排模型的生产接入缝（抖音式「精排模型化」演进点）。
 *
 * <p><b>默认引擎内不提供此 bean</b>：{@link DeepFmRankingModel} 走本地确定性 FM。
 * 生产环境只需额外提供一个 {@code RankModelClient} 实现（例如 HTTP 到 DeepFM / 双塔 serving 推理服务），
 * 并把 {@code turbofeed.feed.rank.remote-model-enabled=true} 打开，即可热切换为远程推理，
 * <b>读路径代码零改动</b>。fail-open：远程调用异常 / 不可用 → 自动退回本地 FM，不阻断推荐页。</p>
 *
 * <p>契约与 {@link RankingModel#score} 完全对齐：输入同一份 {@link RankingFeatures}，
 * 输出「越大越靠前」的分值——这样本地 FM 与远程模型可以无缝互替、可 A/B。</p>
 */
public interface RankModelClient {

    /**
     * 远程精排打分（越大越靠前），语义与 {@link RankingModel#score} 一致。
     *
     * @param features 排序特征（与本地模型同契约）
     * @return 排序分值
     */
    double predict(RankingFeatures features);
}
