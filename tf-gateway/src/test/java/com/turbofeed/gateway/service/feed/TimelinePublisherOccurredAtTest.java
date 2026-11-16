package com.turbofeed.gateway.service.feed;

import com.turbofeed.gateway.client.FeedEngineClient;
import com.turbofeed.gateway.service.query.MediaItem;
import com.turbofeed.gateway.service.review.MediaStatus;
import com.turbofeed.shared.caption.CaptionTagParser;
import com.turbofeed.shared.model.FeedTimelineEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-D3：两条传输通道（HTTP 兜底 / RocketMQ 顺序消息）都必须把 enqueue 时刻透传到引擎侧，
 * 且不能因为新增重载破坏「存量调用点零改动」。
 *
 * <p>MQ 通道额外锁一条：时刻必须落在<b>消息体</b>里。引擎 DLQ 重放把消息原样发回主 topic，
 * 只有消息体能把 enqueue 时刻带回来，MQ 属性会在重放时丢失。</p>
 */
@ExtendWith(MockitoExtension.class)
class TimelinePublisherOccurredAtTest {

    private static final Instant ENQUEUE_AT = Instant.parse("2026-11-16T08:00:00Z");

    @Mock FeedEngineClient feedEngineClient;
    @Mock RocketMQTemplate rocketMQTemplate;

    private static MediaItem post() {
        return new MediaItem("post/1/x", "media/1/a.jpg", "http://img", List.of("http://img"), 0,
                MediaStatus.APPROVED, Instant.parse("2026-11-16T07:00:00Z"), "cap", null,
                CaptionTagParser.parse("cap"), 0L, 0L, false);
    }

    // ==================== HTTP 兜底通道 ====================

    @Test
    void httpChannel_forwardsOccurredAtToEngineClient() {
        when(feedEngineClient.append(any(), eq(2), eq(ENQUEUE_AT))).thenReturn(true);
        when(feedEngineClient.remove(anyString(), eq(ENQUEUE_AT))).thenReturn(true);
        HttpFeedTimelinePublisher publisher = new HttpFeedTimelinePublisher(feedEngineClient);

        assertTrue(publisher.append(post(), 2, ENQUEUE_AT));
        assertTrue(publisher.remove("post/1/x", ENQUEUE_AT));

        verify(feedEngineClient).append(post(), 2, ENQUEUE_AT);
        verify(feedEngineClient).remove("post/1/x", ENQUEUE_AT);
    }

    @Test
    void httpChannel_legacyTwoArgCalls_stillDelegateToOriginalMethods() {
        when(feedEngineClient.append(any(), eq(2))).thenReturn(true);
        when(feedEngineClient.remove(anyString())).thenReturn(true);
        HttpFeedTimelinePublisher publisher = new HttpFeedTimelinePublisher(feedEngineClient);

        assertTrue(publisher.append(post(), 2));
        assertTrue(publisher.remove("post/1/x"));

        // FeedBackfillService 等存量调用点不能因为新增重载而改变行为
        verify(feedEngineClient).append(post(), 2);
        verify(feedEngineClient).remove("post/1/x");
    }

    // ==================== RocketMQ 顺序消息通道 ====================

    @Test
    void mqChannel_appendCarriesEnqueueMomentInMessageBody() {
        RocketMqFeedTimelinePublisher publisher = new RocketMqFeedTimelinePublisher(rocketMQTemplate);
        ReflectionTestUtils.setField(publisher, "topic", "turbofeed-feed-timeline");

        assertTrue(publisher.append(post(), 2, ENQUEUE_AT));

        ArgumentCaptor<FeedTimelineEvent> captor = ArgumentCaptor.forClass(FeedTimelineEvent.class);
        verify(rocketMQTemplate).syncSendOrderly(eq("turbofeed-feed-timeline"), captor.capture(), eq("post/1/x"));
        FeedTimelineEvent sent = captor.getValue();
        assertEquals(ENQUEUE_AT, sent.occurredAt(), "时刻必须在消息体里，DLQ 重放才能原样带回");
        assertEquals(FeedTimelineEvent.ACTION_APPEND, sent.action());
        assertEquals(FeedTimelineEvent.SCHEMA_VERSION, sent.schemaVersion());
    }

    @Test
    void mqChannel_removeCarriesEnqueueMoment() {
        RocketMqFeedTimelinePublisher publisher = new RocketMqFeedTimelinePublisher(rocketMQTemplate);
        ReflectionTestUtils.setField(publisher, "topic", "turbofeed-feed-timeline");

        assertTrue(publisher.remove("post/1/x", ENQUEUE_AT));

        ArgumentCaptor<FeedTimelineEvent> captor = ArgumentCaptor.forClass(FeedTimelineEvent.class);
        verify(rocketMQTemplate).syncSendOrderly(eq("turbofeed-feed-timeline"), captor.capture(), eq("post/1/x"));
        assertEquals(ENQUEUE_AT, captor.getValue().occurredAt());
        assertEquals(FeedTimelineEvent.ACTION_REMOVE, captor.getValue().action());
    }

    @Test
    void mqChannel_legacyTwoArgCalls_keepFactoryTimestamp() {
        RocketMqFeedTimelinePublisher publisher = new RocketMqFeedTimelinePublisher(rocketMQTemplate);
        ReflectionTestUtils.setField(publisher, "topic", "turbofeed-feed-timeline");

        assertTrue(publisher.append(post(), 2));
        assertTrue(publisher.remove("post/1/x"));

        ArgumentCaptor<FeedTimelineEvent> captor = ArgumentCaptor.forClass(FeedTimelineEvent.class);
        verify(rocketMQTemplate, org.mockito.Mockito.times(2))
                .syncSendOrderly(anyString(), captor.capture(), anyString());
        // 两次投递，事件对象仍带工厂生成的时刻（不是 null），契约结构不变
        assertEquals(2, captor.getAllValues().size());
        for (FeedTimelineEvent e : captor.getAllValues()) {
            assertNotNull(e.occurredAt());
            assertEquals(FeedTimelineEvent.SCHEMA_VERSION, e.schemaVersion());
        }
    }
}
