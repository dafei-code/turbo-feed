package com.turbofeed.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 外部 tf-counter 服务客户端配置（绑定 {@code turbofeed.counter.service.*}）。
 *
 * <p>与网关进程内热点计数（{@code turbofeed.counter.bucket-count} 等 tf-hotspot SDK 配置）
 * 同属 {@code turbofeed.counter} 命名空间但相互独立，本类只管「调外部聚合底座」的连接参数。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.counter.service")
public class CounterProperties {

    /** 总开关：默认关。开启后网关点赞才发计数增量给 tf-counter（零回归：默认不触碰外部服务）。 */
    private boolean enabled = false;
    /** tf-counter 直连基址（Nacos 服务发现接入点未来可加 service-name）。 */
    private String baseUrl = "http://localhost:8081";
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofMillis(500);
    /** MQ 削峰时的 Topic（与 tf-counter CounterMqConsumer 共用）。 */
    private String topic = "turbofeed-counter-incr";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
