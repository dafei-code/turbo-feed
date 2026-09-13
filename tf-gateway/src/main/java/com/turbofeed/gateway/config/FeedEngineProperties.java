package com.turbofeed.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Feed 引擎调用配置（{@code turbofeed.feed.*}）。
 *
 * <p>对应 service-split.md §4「gateway → feed-engine 同步 HTTP」。当前为<b>直连形态</b>
 * （{@code engine.base-url} 指向固定 host:port），保证「克隆即跑」；引入 Nacos 服务发现 +
 * Spring Cloud LoadBalancer 后，本配置的 base-url 退化为兜底/调试用，代码不动即可切换。</p>
 *
 * <p>所有参数外部化：调整引擎地址或超时只改配置，不发版。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.feed")
public class FeedEngineProperties {

    /** Feed 引擎（tf-feed-engine）连接配置。 */
    private Engine engine = new Engine();

    /**
     * 引擎不可用时的降级口径。默认 {@link DegradedMode#EMPTY}。
     *
     * <p><b>为什么默认不是回源扫描</b>：网关侧的 {@code MediaJdbcRepository#listApprovedGlobal}
     * 不带分片键，ShardingSphere 会广播到全部分片归并——百亿行下是灾难性的全表扫。把它挂在
     * 生产故障路径上（引擎一抖就打 4 个分片）等于用一次故障引爆整库。因此默认降级为
     * <b>返回空列表 + 明确日志</b>：宁可发现流暂时为空，也不拖垮存储层。
     * 需要演示可用性的本地/demo 环境可显式切到 {@link DegradedMode#LOCAL_SCAN}。</p>
     */
    private DegradedMode degradedMode = DegradedMode.EMPTY;

    /** 引擎不可用时的降级策略。 */
    public enum DegradedMode {

        /** 返回空列表并打 WARN 日志（生产语义：绝不跨分片广播）。 */
        EMPTY,

        /**
         * 回源 {@code listApprovedGlobal} 跨分片广播查询（仅本地/演示）。
         *
         * <p>等价于拆分前的行为，用可用性换正确性；<b>生产禁用</b>。</p>
         */
        LOCAL_SCAN
    }

    /** Feed 引擎连接参数。 */
    public static class Engine {

        /** 引擎基址，如 http://localhost:8082（默认端口见 service-split.md §3）。 */
        private String baseUrl = "http://localhost:8082";

        /** 连接超时：建连失败要快速失败，避免网关线程被慢引擎拖住。 */
        private Duration connectTimeout = Duration.ofMillis(500);

        /** 读取超时：Feed 读是 O(log n) 级 Redis 操作，正常在毫秒级；给足余量即可。 */
        private Duration readTimeout = Duration.ofSeconds(2);

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
    }

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine;
    }

    public DegradedMode getDegradedMode() {
        return degradedMode;
    }

    public void setDegradedMode(DegradedMode degradedMode) {
        this.degradedMode = degradedMode;
    }
}
