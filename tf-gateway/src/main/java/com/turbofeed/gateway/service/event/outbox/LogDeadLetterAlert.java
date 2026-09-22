package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.gateway.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认死信告警：纯日志，按事件类型分级。
 * {@code TIMELINE_REMOVE}（下架）丢失比 {@code TIMELINE_APPEND}（入流）严重——下架内容若持续展示是
 * 正向错误，故 {@code error} 级并标注高优先级；APPEND 用 {@code warn}。
 */
@Component
public class LogDeadLetterAlert implements DeadLetterAlert {

    private static final Logger log = LoggerFactory.getLogger(LogDeadLetterAlert.class);

    @Override
    public void onDead(OutboxEventRepository.OutboxEvent event, Throwable cause) {
        boolean remove = OutboxEventType.TIMELINE_REMOVE.name().equals(event.eventType());
        if (remove) {
            log.error("【死信-高优先级】下架类投递彻底失败且重投耗尽: id={}, type={}, aggregateId={}, attempts={}/{}",
                    event.id(), event.eventType(), event.aggregateId(), event.attemptCount(), event.maxAttempts(), cause);
        } else {
            log.warn("【死信】投递彻底失败且重投耗尽: id={}, type={}, aggregateId={}, attempts={}/{}",
                    event.id(), event.eventType(), event.aggregateId(), event.attemptCount(), event.maxAttempts(), cause);
        }
    }
}
