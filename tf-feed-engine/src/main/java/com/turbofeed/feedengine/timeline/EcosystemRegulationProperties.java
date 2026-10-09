package com.turbofeed.feedengine.timeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生态调控层配置（抖音式「生态调控」G9）。
 *
 * <p>无 Lombok：显式 getter/setter（本机构建环境对新建文件的 Lombok 注解处理不生效）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.ecosystem")
public class EcosystemRegulationProperties {

    /** 是否启用生态调控层（默认开：对流量池主内容源施加全局占比配额）。 */
    private boolean enabled = true;

    /** 单标签在调控页内的最大占比（0~1）：超过则裁剪该标签低分尾部。默认 0.35。 */
    private double maxTagShare = 0.35d;

    /** 单作者在调控页内的最大条数（全局封顶，防垄断）：超过则裁剪低分尾部。默认 3。 */
    private int maxAuthorShare = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getMaxTagShare() {
        return maxTagShare;
    }

    public void setMaxTagShare(double maxTagShare) {
        this.maxTagShare = maxTagShare;
    }

    public int getMaxAuthorShare() {
        return maxAuthorShare;
    }

    public void setMaxAuthorShare(int maxAuthorShare) {
        this.maxAuthorShare = maxAuthorShare;
    }
}
