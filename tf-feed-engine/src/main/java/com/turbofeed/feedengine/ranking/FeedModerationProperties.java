package com.turbofeed.feedengine.ranking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐侧消费审核信号的配置（全部带默认值，<b>不强制改 yml</b>）。
 *
 * <p>抖音式「审核与推荐解耦」：审核信号由网关写成 Redis KV（命名空间 {@code tf:mod:}），
 * 本模块直读。namespace 必须与网关 {@code ModerationSignalService} 的 namespace <b>一致</b>
 * （默认 {@code tf:mod:}），否则读不到信号（fail-open 下退化成「全部放行」）。</p>
 *
 * <p>无 Lombok：显式 getter/setter（本机构建环境对新建文件的 Lombok 注解处理不生效，
 * 与 {@link RankingProperties} 同口径）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.moderation")
public class FeedModerationProperties {

    /** 审核信号 KV 命名空间（须与网关生产者一致，默认 tf:mod:）。 */
    private String namespace = "tf:mod:";

    /** 召回过滤总开关：true = 按内容处置(INTERCEPT/MONITOR) + 低健康分作者剔除公域内容。 */
    private boolean enableRecallFilter = true;

    /** 排序降权总开关：true = 按作者健康分档对内容乘 demoteScale。 */
    private boolean enableHealthDemotion = true;

    /** DEMOTE 档（健康分 [60,80)）的排序系数，与 P0-b「推荐降权 0.5」逐字对齐。 */
    private double demoteScale = 0.5d;

    /** 作者健康分低于该值（&lt;60，含 RESTRICT_SUBMIT/RESTRICT_MONETIZE/BANNED）则剔除公域内容。 */
    private int dropBelowScore = 60;

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public boolean isEnableRecallFilter() {
        return enableRecallFilter;
    }

    public void setEnableRecallFilter(boolean enableRecallFilter) {
        this.enableRecallFilter = enableRecallFilter;
    }

    public boolean isEnableHealthDemotion() {
        return enableHealthDemotion;
    }

    public void setEnableHealthDemotion(boolean enableHealthDemotion) {
        this.enableHealthDemotion = enableHealthDemotion;
    }

    public double getDemoteScale() {
        return demoteScale;
    }

    public void setDemoteScale(double demoteScale) {
        this.demoteScale = demoteScale;
    }

    public int getDropBelowScore() {
        return dropBelowScore;
    }

    public void setDropBelowScore(int dropBelowScore) {
        this.dropBelowScore = dropBelowScore;
    }
}
