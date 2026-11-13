package com.turbofeed.gateway.trace;

import com.turbofeed.shared.trace.TraceIds;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 网关侧 MDC 管道：把当前 traceId 读出来写进消息体，或在 MQ 消费线程上恢复它（M4）。
 *
 * <p><b>为什么不在 tf-shared 里写</b>：tf-shared 的 {@code ArchitectureTest} 禁止任何非 JDK 依赖，
 * 连 {@code org.slf4j.MDC} 都不行。与 {@link TraceIdFilter} 同理——纯逻辑（生成 / 白名单 /
 * 常量）共享在 {@link TraceIds}，MDC 管道按服务各一份，重复三份也比把 servlet / slf4j 依赖
 * 灌进共享库便宜。</p>
 *
 * <p><b>消费侧为什么必须成对调用 {@link #begin} / {@link #end}</b>：RocketMQ 消费线程是复用的，
 * 只 put 不清会把上一批消息的 id 带进下一批，把两笔请求错误地串成一条；而简单
 * {@code MDC.clear()} 又会抹掉外层任务（如 TaskDecorator 传递进来的）上下文。
 * 因此这里保存并恢复<b>完整</b>上下文快照，而不是只 remove 一个键。</p>
 */
public final class TraceContext {

    private TraceContext() {
    }

    /** 当前请求线程的 traceId；非请求线程（定时任务、启动期）返回 {@code null}，不要生成新的。 */
    public static String current() {
        String value = MDC.get(TraceIds.MDC_KEY);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 在消费线程上建立 traceId 上下文。
     *
     * @param rawTraceId 消息体里的原始值；缺失或含非法字符时由 {@link TraceIds#orGenerate} 生成
     * @return 调用前的 MDC 快照，必须传给 {@link #end} 恢复
     */
    public static Map<String, String> begin(String rawTraceId) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put(TraceIds.MDC_KEY, TraceIds.orGenerate(rawTraceId));
        return previous;
    }

    /** 恢复 {@link #begin} 之前的 MDC 快照；务必放在 {@code finally} 中。 */
    public static void end(Map<String, String> previous) {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
