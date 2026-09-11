package com.turbofeed.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * tf-gateway 启动入口。
 *
 * <p>职责：HTTP 接入层，聚合 Feed / 计数 / 热点引擎能力，并承担媒体上传、
 * JWT 认证、流量防护（Sentinel）等网关职责。默认端口 8080，配置见
 * {@code application.yml}。</p>
 *
 * <p>说明：自动扫描仅覆盖 {@code com.turbofeed.gateway} 包及其子包；
 * tf-shared 等内部模块均为纯 POJO（Result / ErrorCode），无需组件扫描。</p>
 *
 * <p>{@link EnableScheduling} 启用 {@code @Scheduled}（敏感词库 30s 定时刷新等）。</p>
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
