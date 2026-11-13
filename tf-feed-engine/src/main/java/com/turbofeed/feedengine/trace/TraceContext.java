package com.turbofeed.feedengine.trace;

import com.turbofeed.shared.trace.InstanceIds;
import com.turbofeed.shared.trace.TraceIds;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 引擎侧 MDC 管道：在 MQ 消费线程上恢复生产者写入消息体的 traceId（M4）。
 *
 * <p><b>为什么按服务各一份</b>：tf-shared 的 {@code ArchitectureTest} 禁止任何非 JDK 依赖，
 * 连 {@code org.slf4j.MDC} 都不行。纯逻辑（生成 / 白名单 / 常量）共享在 {@link TraceIds}，
 * MDC 管道各服务一份，与 {@link TraceIdFilter} 完全同构。</p>
 *
 * <p><b>必须成对调用 {@link #begin} / {@link #end}</b>：RocketMQ 消费线程复用，只 put 不清
 * 会把上一批消息的 id 带进下一批；而简单 {@code MDC.clear()} 又会抹掉外层上下文。
 * 因此保存并恢复完整快照。DLQ 重放把<b>原事件对象</b>重新发回主 topic（见
 * {@code FeedTimelineDlqService#replay}），消息体里的 traceId 会原样跟回来——
 * 这正是把 id 放消息体而非 MQ 属性的原因。</p>
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
     * @param rawTraceId 消息体里的原始值；缺失或含非法字符时由 {@link TraceIds#orGenerate} 生成，
     *                   保证同一次消费的多行日志至少能互相串起来（零退化）
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
     * <p>定时线程不经过 Servlet 过滤器，MDC 原本是空的；生产多副本同时调度时，
     * 一轮内多行日志靠 {@code cycleId} 自关联、靠 {@code instanceId} 区分来源实例。
     * 轮次是一次新的「根」，显式清掉可能残留的 traceId，不沿用任何请求链路。</p>
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
