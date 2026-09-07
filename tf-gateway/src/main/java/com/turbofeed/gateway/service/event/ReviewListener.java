package com.turbofeed.gateway.service.event;

import com.turbofeed.gateway.service.review.MediaReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 审核订阅方（Observer 模式：本地事件消费者）。
 *
 * <p>监听 {@link MediaUploadedEvent}（本地 Spring 事件，由
 * {@code LocalMediaEventPublisher} 发布），收到后驱动审核流转。
 * RocketMQ 模式下本监听器不触发（事件改由 MQ 投递，见 {@code MediaReviewConsumer}），
 * 两者共享同一审核入口 {@link MediaReviewService#handleUploaded}，业务逻辑单一来源。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewListener {

    private final MediaReviewService reviewService;

    @Async("reviewExecutor")
    @EventListener
    public void onMediaUploaded(MediaUploadedEvent event) {
        log.info("本地事件消费: mediaId={}, userId={}", event.mediaId(), event.userId());
        reviewService.handleUploaded(event);
    }
}
