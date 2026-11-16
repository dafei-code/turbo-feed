package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * {@link OutboxEventType#MEDIA_UPLOADED} 的投递处理器（PR-D2）。
 *
 * <p><b>可靠性层与传输层的分工</b>：发件箱保证「media 行与事件行同生共死 + 至少一次投递」，
 * 本 Handler 只决定这一跳用哪种传输把事件送到审核侧。消费端
 * {@code MediaReviewService#handleUploaded} 原本就有 CAS 幂等与兜底落库，<b>不需要改</b>。</p>
 *
 * <p><b>通道由 {@link OutboxChannelResolver} 决定</b>，本类不读 {@code mq.enabled}，
 * 也不散落 {@code if (mqEnabled)}（PR-D5）。LOCAL 走 Spring 应用内事件，行为与默认
 * {@code LocalMediaEventPublisher} 一致；MQ 走与 {@code RocketMqMediaEventPublisher}
 * 相同的 topic / 消息体。</p>
 *
 * <p><b>为什么不直接注入 {@code MediaReviewService}</b>：门面 → ReviewTimelineDelivery →
 * OutboxService → 本 Handler 会形成构造器循环依赖。用事件总线端口既避开环，
 * 又保持与改造前完全相同的下游路径。</p>
 *
 * <p><b>失败语义</b>：任何异常上抛，由发件箱退避重试 / DEAD 告警。
 * 与旧 {@code RocketMqMediaEventPublisher} 的「MQ 失败降级本地事件并当成功」不同——
 * 降级会掩盖失败，而事件行已经把可靠性兜住了。</p>
 */
@Component
public class MediaUploadedHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadedHandler.class);

    private final ObjectMapper objectMapper;
    private final OutboxChannelResolver channels;
    private final ApplicationEventPublisher localPublisher;
    private final ObjectProvider<RocketMQTemplate> rocketMQTemplate;
    private final String topic;

    public MediaUploadedHandler(ObjectMapper objectMapper,
                                OutboxChannelResolver channels,
                                ApplicationEventPublisher localPublisher,
                                ObjectProvider<RocketMQTemplate> rocketMQTemplate,
                                @Value("${turbofeed.media.mq.topic:turbofeed-media-uploaded}") String topic) {
        this.objectMapper = objectMapper;
        this.channels = channels;
        this.localPublisher = localPublisher;
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    @Override
    public OutboxEventType type() {
        return OutboxEventType.MEDIA_UPLOADED;
    }

    @Override
    public void handle(String payloadJson) throws Exception {
        MediaUploadedEvent event = objectMapper.readValue(payloadJson, MediaUploadedEvent.class);
        if (event == null || event.postId() == null || event.postId().isBlank()) {
            throw new IOException("MEDIA_UPLOADED 事件缺少 postId，拒绝投递（留待人工对账）");
        }
        OutboxChannelResolver.Channel channel = channels.channelFor(OutboxEventType.MEDIA_UPLOADED);
        if (channel == OutboxChannelResolver.Channel.MQ) {
            RocketMQTemplate template = rocketMQTemplate.getIfAvailable();
            if (template == null) {
                // 配置错配（通道 = MQ 但没激活 mq profile / name-server）：抛异常留行重试并告警，
                // 绝不静默改走 LOCAL——那会让生产环境的削峰能力悄悄消失。
                throw new IllegalStateException("Outbox MEDIA_UPLOADED 配置为 MQ 通道，但上下文中没有 RocketMQTemplate");
            }
            template.convertAndSend(topic, event);
            log.info("MEDIA_UPLOADED 经 MQ 投递: topic={}, postId={}", topic, event.postId());
        } else {
            localPublisher.publishEvent(event);
            log.info("MEDIA_UPLOADED 经本地事件投递: postId={}", event.postId());
        }
    }
}
