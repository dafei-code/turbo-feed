package com.turbofeed.gateway.service.event;

import com.turbofeed.gateway.service.query.MediaQueryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 查询索引订阅方（Observer 模式：本地事件消费者）。
 *
 * <p>与 {@link ReviewListener} 平级——同一个 {@link MediaUploadedEvent} 的两个独立订阅方：
 * 审核方流转状态，本方建立查询索引，职责互不干扰。RocketMQ 模式下本监听器不触发
 * （事件改由 MQ 投递，见 {@code MediaReviewConsumer}），二者均调用
 * {@link MediaQueryService#index}，索引逻辑单一来源。</p>
 *
 * <p><b>事件顺序说明</b>：本地事件为同步发布，两个订阅方在 {@code publish()} 返回前
 * 依此执行完毕。因此上传接口响应抵达前端时，索引与审核状态均已就绪，
 * 前端随即查询列表不会读到中间态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaIndexListener {

    private final MediaQueryService mediaQueryService;

    @EventListener
    public void onMediaUploaded(MediaUploadedEvent event) {
        mediaQueryService.index(event);
        log.debug("本地事件消费(索引): mediaId={}, userId={}", event.mediaId(), event.userId());
    }
}
