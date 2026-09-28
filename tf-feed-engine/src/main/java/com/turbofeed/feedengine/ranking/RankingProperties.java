package com.turbofeed.feedengine.ranking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 精排打分的配置（全部带默认值，<b>不强制改 yml</b>）。
 *
 * <p>无 Lombok：显式 getter/setter（本机构建环境对新建文件的 Lombok 注解处理不生效）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.rank")
public class RankingProperties {

    /** 完播率目标权重（单位：等效"入流时刻窗口"倍数，见 {@link #recencyWindowMillis}）。 */
    private double completionWeight = 0.15d;

    /** 互动率目标权重。抖音式"互动即优质"，互动成本高于完播，默认给更高权重。 */
    private double interactionWeight = 0.20d;

    /** 兴趣匹配目标权重。 */
    private double interestWeight = 1.0d;

    /**
     * 完播率的先验：相当于先给每条内容虚拟 {@code completionPriorCount} 次曝光，
     * 其中 {@code completionPriorRate} 比例完播。样本越少，结果越贴近这个先验。
     */
    private double completionPriorCount = 20.0d;
    private double completionPriorRate = 0.20d;

    /** 互动率的先验（同完播率口径）。互动天然更稀疏，故先验给得比完播低。 */
    private double interactionPriorCount = 20.0d;
    private double interactionPriorRate = 0.05d;

    /** 兴趣累加分上限（防止少数强互动把某标签权重推到离谱，淹没时间序）。 */
    private double interestScoreCap = 3.0d;

    /** 命中用户负向标签时的惩罚（毫秒；远大于窗口，保证"明确不要"压过"可能喜欢"）。 */
    private double negativePenaltyMillis = 30L * 24 * 3_600_000L;

    /** 各比率目标折算成"等效入流时刻毫秒数"的窗口（默认 1 小时），使 {@code [0,1]} 的比率与 recency 量级可比。 */
    private long recencyWindowMillis = 3_600_000L;

    public double getCompletionWeight() {
        return completionWeight;
    }

    public void setCompletionWeight(double completionWeight) {
        this.completionWeight = completionWeight;
    }

    public double getInteractionWeight() {
        return interactionWeight;
    }

    public void setInteractionWeight(double interactionWeight) {
        this.interactionWeight = interactionWeight;
    }

    public double getInterestWeight() {
        return interestWeight;
    }

    public void setInterestWeight(double interestWeight) {
        this.interestWeight = interestWeight;
    }

    public double getCompletionPriorCount() {
        return completionPriorCount;
    }

    public void setCompletionPriorCount(double completionPriorCount) {
        this.completionPriorCount = completionPriorCount;
    }

    public double getCompletionPriorRate() {
        return completionPriorRate;
    }

    public void setCompletionPriorRate(double completionPriorRate) {
        this.completionPriorRate = completionPriorRate;
    }

    public double getInteractionPriorCount() {
        return interactionPriorCount;
    }

    public void setInteractionPriorCount(double interactionPriorCount) {
        this.interactionPriorCount = interactionPriorCount;
    }

    public double getInteractionPriorRate() {
        return interactionPriorRate;
    }

    public void setInteractionPriorRate(double interactionPriorRate) {
        this.interactionPriorRate = interactionPriorRate;
    }

    public double getInterestScoreCap() {
        return interestScoreCap;
    }

    public void setInterestScoreCap(double interestScoreCap) {
        this.interestScoreCap = interestScoreCap;
    }

    public double getNegativePenaltyMillis() {
        return negativePenaltyMillis;
    }

    public void setNegativePenaltyMillis(double negativePenaltyMillis) {
        this.negativePenaltyMillis = negativePenaltyMillis;
    }

    public long getRecencyWindowMillis() {
        return recencyWindowMillis;
    }

    public void setRecencyWindowMillis(long recencyWindowMillis) {
        this.recencyWindowMillis = recencyWindowMillis;
    }
}
