package com.turbofeed.gateway.service.event;

import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * 高峰期以 MQ 削峰，避免审核逻辑阻塞上传响应。投递失败统一映射
 * {@code DEPENDENCY_UNAVAILABLE}，不裸抛 MQ 异常。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
public class RocketMqMediaEventPublisher implements MediaEventPublisher {

    /** 媒体上传事件 Topic（编译期常量，供发布器与消费者共用）。 */
    public static final String TOPIC = "turbofeed-media-uploaded";

    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public void publish(MediaUploadedEvent event) {
        try {
            rocketMQTemplate.convertAndSend(TOPIC, event);
            log.info("媒体上传事件已投递 RocketMQ: topic={}, mediaId={}", TOPIC, event.mediaId());
        } catch (MessagingException e) {
            log.error("RocketMQ 投递失败: topic={}, mediaId={}", TOPIC, event.mediaId(), e);
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "消息队列暂不可用，请稍后重试");
        }
    }
}
