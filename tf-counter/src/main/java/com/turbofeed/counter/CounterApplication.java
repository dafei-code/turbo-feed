package com.turbofeed.counter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 分布式计数服务入口（独立部署单元，默认端口 8081）。
 *
 * <p>职责：Redis 分桶计数（HINCRBY）+ MQ 削峰 + 批量落库（最终一致）
 * + 三级读缓存（Caffeine L1 / Redis L2 / DB L3）。</p>
 *
 * <p>扫描范围限定 {@code com.turbofeed.counter}（热点治理 SDK 经依赖引入，
 * 由本进程内组件装配，无需跨包扫描）。契约模型位于 tf-shared。</p>
 */
@SpringBootApplication
public class CounterApplication {

    public static void main(String[] args) {
        SpringApplication.run(CounterApplication.class, args);
    }
}
