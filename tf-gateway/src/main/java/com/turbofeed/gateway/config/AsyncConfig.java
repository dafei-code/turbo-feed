package com.turbofeed.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器配置：审核消费端脱离上传线程，削峰 + 防审核慢链路拖垮上传响应。
 *
 * <p><b>为什么需要</b>：上传接口只应做"落存储 + 发事件"即返回受理结果；
 * 审核（机审 + 状态流转 + 写公域时间线）是重 IO，必须异步。本地事件模式下
 * {@code ReviewListener} 经本执行器异步执行；RocketMQ 模式下消费本就在独立进程/线程，
 * 无需本执行器（两者激活互斥）。</p>
 *
 * <p><b>背压策略 CallerRunsPolicy</b>：线程池与队列打满时，由上传线程同步执行审核——
 * 牺牲一点响应速度但绝不错失/丢弃事件（事件最终一致优先于吞吐），避免审核事件丢失
 * 导致内容永久卡在 PENDING。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 审核异步执行器（@Async("reviewExecutor") 显式绑定，避免与框架默认执行器混用）。 */
    @Bean("reviewExecutor")
    public Executor reviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("review-async-");
        // 弹性核心：空闲时回收核心线程（省内存），负载回升时自动重建至 corePoolSize。
        // 与"加节点水平扩容"互补——节点内空闲不空耗资源，突发自动顶上，零新依赖。
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
