package com.turbofeed.gateway.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 事件发布器（事件总线适配器，Observer 的跨进程实现）。
 *
 * <p>由 {@code @ConditionalOnProperty} 控制：{@code turbofeed.mq.enabled=true} 时激活，
 * 与 {@code LocalMediaEventPublisher}（默认本地事件）严格互斥。启用需同时配置
 * {@code rocketmq.name-server} 与 {@code rocketmq.producer.group}（见 application.yml
 * 注释块）。</p>
 *
 * <p>价值：上传事件跨进程投递，消费端（审核 / 清理 / 通知）可独立扩容与失败重试，
 * 高峰期以 MQ 削峰，避免审核逻辑阻塞上传响应。</p>
 *
 * <p>容错降级：MQ 投递失败（no route / 超时 / 连接失败）不向上抛异常阻断上传，
 * 而是降级为本地 {@link ApplicationEventPublisher} 事件，由 {@code ReviewListener}({@code @Async})
 * 在本节点线程池异步消费——功能不中断。降级态牺牲跨实例 rebalance 与持久化（本就是 MQ 职责），
 * 仅作 MQ 抖动/宕机时的兜底；{@code handleUploaded} 已幂等，重复/双消费安全。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
public class RocketMqMediaEventPublisher implements MediaEventPublisher {

    /** 媒体上传事件 Topic（编译期常量，供发布器与消费者共用）。 */
    public static final String TOPIC = "turbofeed-media-uploaded";

    private final RocketMQTemplate rocketMQTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(MediaUploadedEvent event) {
        try {
            rocketMQTemplate.convertAndSend(TOPIC, event);
            log.info("媒体上传事件已投递 RocketMQ: topic={}, mediaId={}", TOPIC, event.mediaId());
        } catch (MessagingException e) {
            // MQ 不可用 → 降级本地线程池消费：上传不失败、审核不中断（幂等保证安全）
            log.warn("RocketMQ 投递失败，降级本地线程处理: topic={}, mediaId={}, {}",
                    TOPIC, event.mediaId(), e.getMessage());
            applicationEventPublisher.publishEvent(event);
        }
    }
}
