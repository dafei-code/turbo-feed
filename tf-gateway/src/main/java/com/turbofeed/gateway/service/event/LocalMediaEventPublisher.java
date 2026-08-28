package com.turbofeed.gateway.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 本地事件发布器（默认实现）：基于 Spring 应用内事件，零外部依赖。
 *
 * <p>由 {@code @ConditionalOnProperty} 保证与 RocketMQ 实现严格互斥：
 * {@code turbofeed.mq.enabled} 未配置或为 false 时激活本实现（matchIfMissing=true
 * 保证克隆即跑），订阅方 {@code ReviewListener} 以 {@code @EventListener} 消费。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "false", matchIfMissing = true)
public class LocalMediaEventPublisher implements MediaEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(MediaUploadedEvent event) {
        applicationEventPublisher.publishEvent(event);
        log.debug("本地事件发布: mediaId={}", event.mediaId());
    }
}
