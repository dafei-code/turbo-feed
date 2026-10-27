package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认死信告警：<b>日志 + 指标</b>（M4 升级，原为纯日志）。
 *
 * <h3>为什么必须补计数</h3>
 * <p>纯日志有两处失效：① 日志是采样给人看的，没有累计值——「今天判死了几次」得 grep；
 * ② 更关键的是<b>日志无法驱动告警</b>：没有指标就没有 {@code increase(...) > 0} 这样的规则，
 * 判死只能被动等人发现。而 DEAD 恰恰是「at-least-once 已经快断了」的信号，
 * 最该在发生的那一刻就吵起来。</p>
 *
 * <h3>指标</h3>
 * <p>{@code tf_outbox_deadletter_total{type}} —— 判死次数，按事件类型分标签。
 * 基数只有两种（{@code TIMELINE_APPEND} / {@code TIMELINE_REMOVE}），安全。</p>
 *
 * <p><b>日志级别不动</b>：{@code TIMELINE_REMOVE}（下架）丢失比 {@code TIMELINE_APPEND}（入流）严重——
 * 下架内容若持续展示是正向错误，故 {@code error} 级并标注高优先级；APPEND 用 {@code warn}。
 * 指标侧的严重度差异由告警规则按 {@code type} 标签区分，不在指标层面做去重。</p>
 *
 * <p>为什么仍叫「默认实现」而不直接叫 {@code PrometheusDeadLetterAlert}：接飞书/钉钉时换 Bean 即可，
 * 指标是下限而非上限。真正的告警通道（电话、值班群）由部署侧在此基础上叠加。</p>
 */
@Component
public class DefaultDeadLetterAlert implements DeadLetterAlert {

    private static final Logger log = LoggerFactory.getLogger(DefaultDeadLetterAlert.class);

    /** 指标名：Prometheus 侧暴露为 {@code tf_outbox_deadletter_total}。 */
    static final String METRIC_NAME = "tf_outbox_deadletter_total";

    private final MeterRegistry registry;

    public DefaultDeadLetterAlert(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onDead(OutboxEventRepository.OutboxEvent event, Throwable cause) {
        boolean remove = OutboxEventType.TIMELINE_REMOVE.name().equals(event.eventType());
        // 计数先行：即使下面的日志因磁盘/编码问题失败，指标也已经记上了。
        // 标签值直接取事件类型字符串——枚举只有两个值，不会造成标签爆炸。
        Counter.builder(METRIC_NAME)
                .description("发件箱判死次数（事件彻底失败且重投耗尽）")
                .tag("type", String.valueOf(event.eventType()))
                .register(registry)
                .increment();
        if (remove) {
            log.error("【死信-高优先级】下架类投递彻底失败且重投耗尽: id={}, type={}, aggregateId={}, attempts={}/{}",
                    event.id(), event.eventType(), event.aggregateId(), event.attemptCount(), event.maxAttempts(), cause);
        } else {
            log.warn("【死信】投递彻底失败且重投耗尽: id={}, type={}, aggregateId={}, attempts={}/{}",
                    event.id(), event.eventType(), event.aggregateId(), event.attemptCount(), event.maxAttempts(), cause);
        }
    }
}
