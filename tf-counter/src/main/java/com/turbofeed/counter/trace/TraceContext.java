package com.turbofeed.counter.trace;

import com.turbofeed.shared.trace.InstanceIds;
import com.turbofeed.shared.trace.TraceIds;
import org.slf4j.MDC;

import java.util.Map;

/**
 * tf-counter 侧 MDC 管道：在 MQ 消费线程上恢复生产者写入消息体的 traceId（M4）。
 *
 * <p><b>为什么按服务各一份</b>：tf-shared 的 {@code ArchitectureTest} 禁止任何非 JDK 依赖。
 * 纯逻辑共享在 {@link TraceIds}，MDC 管道各服务一份。</p>
 *
 * <p><b>本服务还有一条 HTTP 兜底入口</b>（{@code CounterIncrController#incr}，MQ 关闭时
 * 网关直连），由 {@link TraceIdFilter} 从 {@code X-Trace-Id} 取 id；两条入口写的是同一个
 * MDC 键，日志格式也统一为 {@code %5p [%X{traceId:-}]}。</p>
 */
public final class TraceContext {

    private TraceContext() {
    }

    /** 当前线程的 traceId；非请求线程返回 {@code null}，不生成新的。 */
    public static String current() {
        String value = MDC.get(TraceIds.MDC_KEY);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 建立消费上下文。
     *
     * @param rawTraceId 消息体里的原始值；缺失或含非法字符时由 {@link TraceIds#orGenerate} 生成
     * @return 调用前的 MDC 快照，必须传给 {@link #end}
     */
    public static Map<String, String> begin(String rawTraceId) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put(TraceIds.MDC_KEY, TraceIds.orGenerate(rawTraceId));
        MDC.put(TraceIds.MDC_INSTANCE_KEY, InstanceIds.current());
        return previous;
    }

    /**
     * 开启一轮定时调度上下文，返回调用前的 MDC 快照（务必在 {@code finally} 里传给 {@link #end}）。
     *
     * <p>计数 flush 由 standalone / clustered 两种调度器驱动，多副本下同一秒会有多行
     * 聚合日志；{@code cycleId} 让同一轮自关联，{@code instanceId} 区分来源实例。
     * 轮次是一次新的「根」，显式清掉可能残留的 traceId。</p>
     */
    public static Map<String, String> beginCycle() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.remove(TraceIds.MDC_KEY);
        MDC.put(TraceIds.MDC_CYCLE_KEY, TraceIds.generate());
        MDC.put(TraceIds.MDC_INSTANCE_KEY, InstanceIds.current());
        return previous;
    }

    /** 恢复 {@link #begin} / {@link #beginCycle} 之前的 MDC 快照；务必放在 {@code finally} 中。 */
    public static void end(Map<String, String> previous) {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
