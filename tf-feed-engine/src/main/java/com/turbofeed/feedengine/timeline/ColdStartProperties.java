package com.turbofeed.feedengine.timeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 冷启动探索池配置（抖音式「探索利用 EE」G7）。
 *
 * <p>无 Lombok：显式 getter/setter（本机构建环境对新建文件的 Lombok 注解处理不生效）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed.coldstart")
public class ColdStartProperties {

    /** 是否启用冷启动探索池（默认开：给新内容 / 低曝光内容固定曝光配额，避免饿死）。 */
    private boolean enabled = true;

    /** 探索配额占单页比例：本页至少注入 floor(limit × ratio) 条「新鲜内容」。 */
    private double ratio = 0.15d;

    /** 「新鲜」窗口（小时）：入流时刻在 now-windowHours 内的内容视为待探索冷内容。 */
    private long windowHours = 6L;

    /** 探索池只从 L1/L2（新内容试水池）取候选，避免把已验证优质老内容再当冷启动塞回来。 */
    private int maxPool = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getRatio() {
        return ratio;
    }

    public void setRatio(double ratio) {
        this.ratio = ratio;
    }

    public long getWindowHours() {
        return windowHours;
    }

    public void setWindowHours(long windowHours) {
        this.windowHours = windowHours;
    }

    public int getMaxPool() {
        return maxPool;
    }

    public void setMaxPool(int maxPool) {
        this.maxPool = maxPool;
    }
}
