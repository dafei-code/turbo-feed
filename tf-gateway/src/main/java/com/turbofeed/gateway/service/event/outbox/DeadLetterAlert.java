package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;

/**
 * 死信告警（事件彻底失败、重投耗尽时触发）。
 *
 * <p>默认实现 {@link LogDeadLetterAlert} 打日志（REMOVE 类高严重度）；可替换为接 Prometheus Counter
 * / 飞书 / 钉钉等，只要换一个 {@code DeadLetterAlert} 的 Bean 即可，发件箱核心逻辑不感知具体通道。</p>
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
