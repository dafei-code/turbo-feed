package com.turbofeed.feedengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Feed 推拉结合引擎入口（独立部署单元，默认端口 8082）。
 *
 * <p>职责：收件箱（Redis List + LTRIM）/ 大 V outbox（ZSET）/ 活跃度分层
 * / 扇出 Worker / 多路归并聚合。</p>
 *
 * <p>扫描范围限定 {@code com.turbofeed.feedengine}（热点治理 SDK 经依赖引入，
 * 由本进程内组件装配，无需跨包扫描）。契约模型位于 tf-shared。</p>
 */
@SpringBootApplication
public class FeedEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeedEngineApplication.class, args);
    }
}
