package com.turbofeed.gateway.service.event;

import com.turbofeed.gateway.service.query.MediaQueryService;
import com.turbofeed.gateway.service.review.MediaReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 审核消费者（Observer 模式：跨进程事件订阅方）。
 *
 * <p>与 {@code ReviewListener}（本地 Spring 事件）职责相同、激活互斥：
 * {@code turbofeed.mq.enabled=true} 时由本类消费 MQ 消息，二者共用
 * {@link MediaReviewService#handleUploaded} 单一审核入口。</p>
 *
 * <p>消费语义：默认并发消费 + 失败重试（RocketMQ 默认 16 次），消费失败会触发
 * 重投，因此审核处理需具备幂等性（{@code MediaReviewService} 对重复提交抛异常
 * 而非重复流转，配合重试语义是安全的——重复消息不会导致状态二次变更）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = RocketMqMediaEventPublisher.TOPIC,
        consumerGroup = "turbofeed-media-review-group")
public class MediaReviewConsumer implements RocketMQListener<MediaUploadedEvent> {

    private final MediaReviewService reviewService;
    private final MediaQueryService mediaQueryService;

    @Override
    public void onMessage(MediaUploadedEvent event) {
        log.info("MQ 消费媒体上传事件: mediaId={}, userId={}", event.mediaId(), event.userId());
        reviewService.handleUploaded(event);
        // 与 ReviewListener 侧的 MediaIndexListener 对齐：MQ 模式下本地事件不再发布，
        // 查询索引须在此同步建立，否则"我的上传"列表在 MQ 模式下恒为空
        mediaQueryService.index(event);
    }
}
