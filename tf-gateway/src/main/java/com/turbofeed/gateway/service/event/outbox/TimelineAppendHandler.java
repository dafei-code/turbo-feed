package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.service.feed.FeedTimelinePublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * {@link OutboxEventType#TIMELINE_APPEND} 的投递处理器（PR-C3 从 {@code OutboxService#dispatch} 拆出）。
 *
 * <p>逻辑与原 switch 分支逐字等价，只多了一处<b>失败可见化</b>：
 * {@link FeedTimelinePublisher#append} 返回 {@code false} 表示「这次没送达」，原实现忽略返回值、
 * 仍然把事件标 SENT——发件箱本该把失败留在库里重试，这里改成抛异常走退避。
 * 这是分发注册表改造顺手补上的静默丢失点，不改变成功路径行为。</p>
 *
 * <p><b>PR-D3</b>：把 payload 里的 enqueue 时刻透传给带时刻重载，引擎侧据此做墓碑防旧。
 * 绝不能在这里用 {@code Instant.now()}——DEAD 行重投时刷新时间戳会让已下架内容复活。
 * 存量事件行无该字段 → {@code null} → 引擎侧放行。</p>
 */
@Component
public class TimelineAppendHandler implements OutboxEventHandler {

    private final ObjectMapper objectMapper;
    private final FeedTimelinePublisher feedTimelinePublisher;

    public TimelineAppendHandler(ObjectMapper objectMapper, FeedTimelinePublisher feedTimelinePublisher) {
        this.objectMapper = objectMapper;
        this.feedTimelinePublisher = feedTimelinePublisher;
    }

    @Override
    public OutboxEventType type() {
        return OutboxEventType.TIMELINE_APPEND;
    }

    @Override
    public void handle(String payloadJson) throws Exception {
        TimelineAppendPayload payload = objectMapper.readValue(payloadJson, TimelineAppendPayload.class);
        if (!feedTimelinePublisher.append(payload.item(), payload.poolLevel(), payload.occurredAt())) {
            throw new IOException("时间线 APPEND 未送达: timelineKey="
                    + (payload.item() == null ? "null" : payload.item().timelineKey()));
        }
    }
}
