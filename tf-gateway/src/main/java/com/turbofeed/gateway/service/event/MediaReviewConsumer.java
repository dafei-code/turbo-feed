package com.turbofeed.gateway.service.event;

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
 * <p>消费语义：并发消费 + 失败重试（{@code maxReconsumeTimes=3}），消费抛异常触发
 * RocketMQ 重投，重试耗尽进入死信队列 {@code %DLQ%<consumerGroup>}，由人工 / 巡检对账。
 * 审核逻辑已幂等（{@code MediaReviewService#handleUploaded} 带 {@code @Transactional} 且终态幂等返回），
 * 重投不会导致状态二次变更或重复入公域时间线。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "turbofeed.mq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${turbofeed.media.mq.topic:turbofeed-media-uploaded}",
        consumerGroup = "${turbofeed.media.mq.consumer-group:turbofeed-media-review-group}",
        maxReconsumeTimes = 3,   // 消费失败最多重试 3 次（不含首投），超限自动进入 %DLQ%{consumerGroup}
        consumeThreadMax = 32)   // 消费并发线程上限，按节点核数与审核耗时调优
public class MediaReviewConsumer implements RocketMQListener<MediaUploadedEvent> {

    private final MediaReviewService reviewService;

    @Override
    public void onMessage(MediaUploadedEvent event) {
        log.info("MQ 消费媒体上传事件: mediaId={}, userId={}", event.mediaId(), event.userId());
        // 媒体行落库与审核翻转统一在 handleUploaded 内完成（insert PENDING -> review APPROVED），
        // 与本地事件模式共用单一入口；查询走 media 表，无需此处再建内存索引。
        try {
            reviewService.handleUploaded(event);
        } catch (Exception e) {
            // 抛异常 → RocketMQ 按 maxReconsumeTimes 重投；重投耗尽进入死信队列 %DLQ%{consumerGroup}，
            // 由人工 / 巡检对账处理。审核逻辑已幂等，重投不会产生重复终态。
            log.error("审核消费失败，触发 RocketMQ 重试/DLQ: mediaId={}, userId={}",
                    event.mediaId(), event.userId(), e);
            throw e;
        }
    }
}
