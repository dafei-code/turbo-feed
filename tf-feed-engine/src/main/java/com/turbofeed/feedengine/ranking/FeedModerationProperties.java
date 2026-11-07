package com.turbofeed.feedengine.ranking;

import com.turbofeed.shared.redis.RedisKeys;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐侧消费审核信号的配置（全部带默认值，<b>不强制改 yml</b>）。
 *
 * <p>抖音式「审核与推荐解耦」：审核信号由网关写成 Redis KV（命名空间 {@code tf:mod:}），
 * 本模块直读。namespace 必须与网关 {@code ModerationSignalService} 的 namespace <b>一致</b>
 * （默认 {@code tf:mod:}），否则读不到信号（fail-open 下退化成「全部放行」）。</p>
 *
 * <p><b>两类降权相互独立（ADR-0014）</b>：
 * <ul>
 *   <li>作者级：{@code tf:mod:account} 的 {@code tier=DEMOTE} → {@link #demoteScale}；</li>
 *   <li>内容级：{@code tf:mod:media} 的 {@code action=DEMOTE} → {@link #contentDemoteScale}。</li>
 * </ul>
 * 二者最终相乘作用于精排分；各自的开关也独立，便于分别灰度与回滚。
 * 注意 {@code action=MONITOR} 不是降权，而是召回层/搜索层的<b>硬剔除</b>。</p>
 *
 * <p>无 Lombok：显式 getter/setter（本机构建环境对新建文件的 Lombok 注解处理不生效，
 * 与 {@link RankingProperties} 同口径）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.moderation")
public class FeedModerationProperties {

    /** 审核信号 KV 命名空间（须与网关生产者一致，默认 tf:mod:）。 */
    private String namespace = RedisKeys.MODERATION_DEFAULT_NAMESPACE;

    /** 召回过滤总开关：true = 按内容处置(INTERCEPT/MONITOR) + 低健康分作者剔除公域内容。 */
    private boolean enableRecallFilter = true;

    /** 排序降权总开关（作者级）：true = 按作者健康分档对内容乘 demoteScale。 */
    private boolean enableHealthDemotion = true;

    /** 作者级 DEMOTE 档（健康分 [60,80)）的排序系数，与 P0-b「推荐降权 0.5」逐字对齐。 */
    private double demoteScale = 0.5d;

    /**
     * 内容级轻降权总开关（ADR-0014）：true = 按 {@code tf:mod:media.action=DEMOTE}
     * 对内容乘 {@link #contentDemoteScale}。关闭即回退到「LIMIT 信号写了但引擎不消费」，
     * 用于灰度与紧急止血（不影响 INTERCEPT/MONITOR 的召回硬剔除）。
     */
    private boolean enableContentDemotion = true;

    /**
     * 内容级 DEMOTE（机审 LIMIT 裁定）的排序系数。
     *
     * <p>默认 {@code 0.7}，刻意比作者级 {@code demoteScale=0.5} 温和：LIMIT 是
     * {@code ModerationAction} 中最轻的非 PASS 档，对应「边界敏感但未确认违规」，
     * 内容仍留在公域发现流、仍可被搜索、仍可播放，只在精排上稍微靠后。
     * 调成 1.0 等价于关闭内容降权，调成 0.0 等价于排序沉底（但不剔除）。</p>
     */
    private double contentDemoteScale = 0.7d;

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

    public boolean isEnableContentDemotion() {
        return enableContentDemotion;
    }

    public void setEnableContentDemotion(boolean enableContentDemotion) {
        this.enableContentDemotion = enableContentDemotion;
    }

    public double getContentDemoteScale() {
        return contentDemoteScale;
    }

    public void setContentDemoteScale(double contentDemoteScale) {
        this.contentDemoteScale = contentDemoteScale;
    }

    public int getDropBelowScore() {
        return dropBelowScore;
    }

    public void setDropBelowScore(int dropBelowScore) {
        this.dropBelowScore = dropBelowScore;
    }
}
