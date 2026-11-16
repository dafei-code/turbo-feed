package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.service.feed.FeedTimelinePublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * {@link OutboxEventType#TIMELINE_REMOVE} 的投递处理器（PR-C3 从 {@code OutboxService#dispatch} 拆出）。
 *
 * <p>下架是<b>安全动作</b>：投递失败比投递晚了更糟（已下架内容会继续在公域展示），
 * 因此 {@link FeedTimelinePublisher#remove} 返回 {@code false} 时同样抛异常留行重试，
 * 不能像原 switch 分支那样吞掉返回值直接标 SENT。</p>
 *
 * <p><b>PR-D3</b>：透传 enqueue 时刻，引擎侧把它写成墓碑，用于拦截之后重投的陈旧 APPEND。
 * 存量事件行无该字段 → {@code null} → 不写墓碑，历史行为零回归。</p>
 */
@Component
public class TimelineRemoveHandler implements OutboxEventHandler {

    private final ObjectMapper objectMapper;
    private final FeedTimelinePublisher feedTimelinePublisher;

    public TimelineRemoveHandler(ObjectMapper objectMapper, FeedTimelinePublisher feedTimelinePublisher) {
        this.objectMapper = objectMapper;
        this.feedTimelinePublisher = feedTimelinePublisher;
    }

    @Override
    public OutboxEventType type() {
        return OutboxEventType.TIMELINE_REMOVE;
    }

    @Override
    public void handle(String payloadJson) throws Exception {
        TimelineRemovePayload payload = objectMapper.readValue(payloadJson, TimelineRemovePayload.class);
        if (!feedTimelinePublisher.remove(payload.timelineKey(), payload.occurredAt())) {
            throw new IOException("时间线 REMOVE 未送达: timelineKey=" + payload.timelineKey());
        }
    }
}
