package com.turbofeed.feedengine.timeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 重排多样性配置（抖音式「同类不连刷 + 作者去重 + 标签打散」上下文感知重排 G8）。
 *
 * <p>无 Lombok：显式 getter/setter（本机构建环境对新建文件的 Lombok 注解处理不生效）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.diversity")
public class DiversityRerankProperties {

    /** 是否启用上下文感知重排（默认开；与改造前的 diversity-window 语义对齐，但升级为多信号）。 */
    private boolean enabled = true;

    /** 滑动窗口大小：作者去重 / 标签重叠判定只看最近 window 条已输出内容。 */
    private int window = 3;

    /** 作者去重惩罚权重：同一作者在窗口内已出现 k 次 → 扣 k × 该权重（display 序位单位）。 */
    private double authorPenaltyWeight = 2.0d;

    /** 标签重叠惩罚权重：与窗口内内容的最大 Jaccard 标签重叠 × 该权重（display 序位单位）。 */
    private double tagOverlapPenalty = 1.0d;

    /** 同作者单窗口内最大允许出现次数（超过即按 authorPenaltyWeight 强罚，避免单一作者霸屏）。 */
    private int authorCapPerWindow = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWindow() {
        return window;
    }

    public void setWindow(int window) {
        this.window = window;
    }

    public double getAuthorPenaltyWeight() {
        return authorPenaltyWeight;
    }

    public void setAuthorPenaltyWeight(double authorPenaltyWeight) {
        this.authorPenaltyWeight = authorPenaltyWeight;
    }

    public double getTagOverlapPenalty() {
        return tagOverlapPenalty;
    }

    public void setTagOverlapPenalty(double tagOverlapPenalty) {
        this.tagOverlapPenalty = tagOverlapPenalty;
    }

    public int getAuthorCapPerWindow() {
        return authorCapPerWindow;
    }

    public void setAuthorCapPerWindow(int authorCapPerWindow) {
        this.authorCapPerWindow = authorCapPerWindow;
    }
}
