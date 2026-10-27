package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;

/**
 * 死信告警（事件彻底失败、重投耗尽时触发）。
 *
 * <p>默认实现 {@link DefaultDeadLetterAlert} 打日志（REMOVE 类高严重度）<b>并计数</b>
 * （{@code tf_outbox_deadletter_total{type}}，M4 补齐）；可替换为接飞书 / 钉钉 / 值班电话等，
 * 只要换一个 {@code DeadLetterAlert} 的 Bean 即可，发件箱核心逻辑不感知具体通道。</p>
 *
 * <p><b>换成自定义实现时注意</b>：请不要把默认实现的指标丢掉。告警通道决定「谁被叫醒」，
 * 指标决定「能不能写告警规则」——两者都要，缺指标的告警只能靠人肉盯日志。</p>
 */
public interface DeadLetterAlert {

    /**
     * 事件判死时触发。
     *
     * @param event 判死的发件箱行（含类型/聚合标识/尝试次数）
     * @param cause 最后一次投递异常
     */
    void onDead(OutboxEventRepository.OutboxEvent event, Throwable cause);
}
