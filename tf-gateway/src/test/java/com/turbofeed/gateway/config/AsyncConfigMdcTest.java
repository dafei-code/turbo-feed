package com.turbofeed.gateway.config;

import com.turbofeed.shared.trace.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M4：4 个异步执行器必须把提交时刻的 MDC 带进任务线程。
 *
 * <p>不测「有没有配 TaskDecorator」（那是实现细节），只测行为：在主线程设好 id，
 * 提交一个任务，在任务线程里读到的必须是同一个值；任务结束后主线程的 MDC 不受影响。</p>
 */
class AsyncConfigMdcTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        MDC.clear();
    }

    @Test
    @DisplayName("reviewExecutor：异步任务里能读到提交请求的 traceId")
    void reviewExecutor_propagatesMdc() throws Exception {
        assertPropagates((ThreadPoolTaskExecutor) new AsyncConfig().reviewExecutor());
    }

    @Test
    @DisplayName("uploadFinalizeExecutor：异步任务里能读到提交请求的 traceId")
    void uploadFinalizeExecutor_propagatesMdc() throws Exception {
        assertPropagates((ThreadPoolTaskExecutor) new AsyncConfig().uploadFinalizeExecutor());
    }

    private void assertPropagates(ThreadPoolTaskExecutor pool) throws Exception {
        this.executor = pool;
        MDC.put(TraceIds.MDC_KEY, "probe-abc123");

        Future<String> seen = executor.submit(() -> MDC.get(TraceIds.MDC_KEY));
        assertEquals("probe-abc123", seen.get(),
                "异步落库 / 审核失败的那行 warn 若没有 id，就无法关联回原请求");
        assertEquals("probe-abc123", MDC.get(TraceIds.MDC_KEY), "提交方线程的 MDC 不应被改动");
    }
}
