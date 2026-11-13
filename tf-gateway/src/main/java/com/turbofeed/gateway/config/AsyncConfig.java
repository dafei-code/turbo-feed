package com.turbofeed.gateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /** 审核异步执行器（@Async("reviewExecutor") 显式绑定，避免与框架默认执行器混用）。 */
    @Bean("reviewExecutor")
    public Executor reviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("review-async-");
        executor.setTaskDecorator(mdcPropagating());
        // 弹性核心：空闲时回收核心线程（省内存），负载回升时自动重建至 corePoolSize。
        // 与"加节点水平扩容"互补——节点内空闲不空耗资源，突发自动顶上，零新依赖。
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 预签名直传的收尾执行器（{@code @Async("uploadFinalizeExecutor")} 显式绑定）。
     *
     * <p><b>为什么与 reviewExecutor 分开</b>：两者虽然都是「上传之后的异步尾巴」，
     * 但语义不同——本执行器跑的是<b>落库前的收尾</b>（可选的处理链重编码 + 整帖落库 + 发事件），
     * 一旦堆积，被拖慢的是「内容可见时延」；而 reviewExecutor 堆积拖慢的是「审核时延」。
     * 分开后互不抢占：审核洪峰不会让刚传完的内容迟迟不入「我的内容」。</p>
     *
     * <p>背压同用 CallerRunsPolicy：池与队列打满时退回调用线程同步执行收尾，
     * 宁可让 complete 接口变慢，也不丢弃任务（丢弃 = 内容永久停在 PENDING）。</p>
     */
    @Bean("uploadFinalizeExecutor")
    public Executor uploadFinalizeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(512);
        executor.setThreadNamePrefix("upload-finalize-");
        executor.setTaskDecorator(mdcPropagating());
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 交互事件执行器（{@code @Async("interactionExecutor")} 显式绑定）。
     *
     * <p><b>为什么独立</b>：交互事件（曝光/点击/完播…）写放大远高于审核/上传尾巴，且失败
     * 仅丢训练样本（fail-open）。独立池避免与 reviewExecutor / uploadFinalizeExecutor 互相抢占，
     * 且可给更大队列吸收推荐流突发。背压同用 CallerRunsPolicy：池与队列打满时退回调用线程
     * 同步写，宁可让推荐接口变慢，也不静默丢弃样本（样本丢失 = 模型学偏）。</p>
     */
    @Bean("interactionExecutor")
    public Executor interactionExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1024);
        executor.setThreadNamePrefix("interaction-");
        executor.setTaskDecorator(mdcPropagating());
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new CountingCallerRunsPolicy(meterRegistry));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 可丢异步执行器（{@code @Async("bestEffortExecutor")} / 直接 execute 绑定）。
     *
     * <p><b>适用</b>：fail-open 的旁路异步活——通知生成、交互行为样本落库。这类任务丢失不影响
     * 主链路正确性（通知丢失只是少一条角标；样本丢失只是模型学偏一点），但<b>绝不可阻塞业务线程</b>。</p>
     *
     * <p><b>背压策略 DiscardWithMetricPolicy</b>：池与队列打满时<b>直接丢弃</b>任务（与 interactionExecutor 的
     * CallerRunsPolicy 相反），并计数 {@code gateway.async.best_effort.dropped} + 采样 WARN——
     * 把「不阻塞主链路」的承诺在高压下真正兑现，代价是极端过载时少量可丢任务被丢弃（有指标可观测，
     * 经 /actuator/prometheus 暴露）。</p>
     */
    @Bean("bestEffortExecutor")
    public Executor bestEffortExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("best-effort-");
        executor.setTaskDecorator(mdcPropagating());
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new DiscardWithMetricPolicy(meterRegistry, log));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 把提交任务那一刻的 MDC 快照带进异步线程（M4）。
     *
     * <p><b>为什么需要</b>：审核、上传收尾、交互落库、可丢旁路这 4 个池子跑的都是「请求的下半段」。
     * 改造前 MDC 是 ThreadLocal，任务一进池子日志里就变成 {@code []}——例如
     * {@code MediaLikeService} 的 fire-and-forget 落库失败只打一行 warn，
     * 这行 warn 无法关联回是哪次点赞请求产生的，等于没有排查线索。</p>
     *
     * <p><b>为什么保存 / 恢复 previous 而不是直接 clear</b>：这些 executor 是池化的，
     * 任务 A 结束后直接 clear 会抹掉线程上可能存在的另一层上下文（嵌套调用、
     * CallerRunsPolicy 让任务退回调用线程同步执行时尤其明显）。保存后恢复是幂等写法，
     * 代价是两次 map 拷贝。</p>
     *
     * <p><b>必须在 {@code initialize()} 之前 set</b>：{@code ThreadPoolTaskExecutor} 在
     * 初始化执行器时读取 decorator，之后再设不会作用到已创建的线程池。</p>
     */
    private static TaskDecorator mdcPropagating() {
        return runnable -> {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (captured == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(captured);
                }
                try {
                    runnable.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }

    /**
     * 可丢任务拒绝策略：池与队列饱和时<b>直接丢弃</b>任务（fail-open 语义），并计数 + 采样告警。
     * 用于通知生成 / 交互行为样本这类「丢失不影响主链路正确性」的旁路异步活。绝不阻塞调用方线程。
     */
    static class DiscardWithMetricPolicy implements RejectedExecutionHandler {
        private final Counter droppedCounter;
        private final Logger log;
        private final AtomicLong sinceLastWarn = new AtomicLong(0);

        DiscardWithMetricPolicy(MeterRegistry meterRegistry, Logger log) {
            this.droppedCounter = meterRegistry.counter("gateway.async.best_effort.dropped");
            this.log = log;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            droppedCounter.increment();
            long n = sinceLastWarn.incrementAndGet();
            if (n % 100 == 0) {
                log.warn("best-effort 执行器饱和，丢弃可丢异步任务(fail-open): 累计丢弃≈{}, 活跃线程={}, 队列余量={}",
                        n, executor.getActiveCount(), executor.getQueue().remainingCapacity());
            }
        }
    }

    /**
     * CallerRuns 计数包装：保留「饱和时退回调用线程同步执行、绝不丢任务」语义，
     * 但额外计数 {@code gateway.async.interaction.saturated}，暴露持久化执行器的背压信号
     * （一旦开始 caller-runs，说明上传 / 点赞等主链路已被异步尾巴拖慢，是容量红线告警源）。
     */
    static class CountingCallerRunsPolicy extends ThreadPoolExecutor.CallerRunsPolicy {
        private final Counter saturatedCounter;

        CountingCallerRunsPolicy(MeterRegistry meterRegistry) {
            this.saturatedCounter = meterRegistry.counter("gateway.async.interaction.saturated");
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            saturatedCounter.increment();
            super.rejectedExecution(r, executor);
        }
    }
}
