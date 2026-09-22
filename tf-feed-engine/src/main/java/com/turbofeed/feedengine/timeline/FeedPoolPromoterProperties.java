package com.turbofeed.feedengine.timeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 流量池晋级阈值（原硬编码在 {@link FeedPoolPromoter} 的常量）。
 *
 * <p>全部带默认值，<b>不强制改 yml</b>：未配置时与改造前行为完全一致。
 * 要调参时在 feed-engine 的 application.yml 加 {@code turbofeed.feed.pool.*} 即可，无需发版。
 * 完播权重低于主动互动，符合短视频"看完≠喜欢"的直觉（见 FeedPoolPromoter 类注释）。</p>
 *
 * <p>无 Lombok：显式 getter/setter（本机构建环境对新建文件的 Lombok 注解处理不生效）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.pool")
public class FeedPoolPromoterProperties {

    /** 样本不足（曝光 < 此值）不晋级，避免小样本误判。 */
    private long minImpressions = 20L;

    /** L1 → L2 所需互动率阈值。 */
    private double p1ToP2Rate = 0.05d;

    /** L2 → L3 所需互动率阈值。 */
    private double p2ToP3Rate = 0.08d;

    /** 完播在互动率里的权重（主动互动=1）。 */
    private double playCompleteWeight = 0.5d;

    /** 差评率（dislikes/impressions）≥ 此值 → 锁在/打回 L1，不给公域放大。 */
    private double dislikeDemoteRatio = 0.30d;

    public long getMinImpressions() {
        return minImpressions;
    }

    public void setMinImpressions(long minImpressions) {
        this.minImpressions = minImpressions;
    }

    public double getP1ToP2Rate() {
        return p1ToP2Rate;
    }

    public void setP1ToP2Rate(double p1ToP2Rate) {
        this.p1ToP2Rate = p1ToP2Rate;
    }

    public double getP2ToP3Rate() {
        return p2ToP3Rate;
    }

    public void setP2ToP3Rate(double p2ToP3Rate) {
        this.p2ToP3Rate = p2ToP3Rate;
    }

    public double getPlayCompleteWeight() {
        return playCompleteWeight;
    }

    public void setPlayCompleteWeight(double playCompleteWeight) {
        this.playCompleteWeight = playCompleteWeight;
    }

    public double getDislikeDemoteRatio() {
        return dislikeDemoteRatio;
    }

    public void setDislikeDemoteRatio(double dislikeDemoteRatio) {
        this.dislikeDemoteRatio = dislikeDemoteRatio;
    }
}
