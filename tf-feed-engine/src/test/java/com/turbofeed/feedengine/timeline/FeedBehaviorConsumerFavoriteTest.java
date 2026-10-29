package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.logging.BehaviorLogSink;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 行为消费者对收藏信号的路由护栏：{@code UNFAVORITE} 必须走 {@code cancel}（与 {@code UNLIKE} 同款），
 * 不能错路由到 {@code record}（否则实时分不会回退）。
 */
@ExtendWith(MockitoExtension.class)
class FeedBehaviorConsumerFavoriteTest {

    @Mock PostStatService postStatService;
    @Mock InterestService interestService;
    @Mock SessionSequenceService sessionSequenceService;
    @Mock BehaviorLogSink behaviorLogSink;

    private FeedBehaviorConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new FeedBehaviorConsumer(postStatService, interestService, sessionSequenceService, behaviorLogSink);
    }

    @Test
    void unfavorite_routesToCancel_notRecord() {
        consumer.onMessage(new FeedBehaviorEvent("k", "UNFAVORITE", null, null, "1", null, null));

        verify(postStatService).cancel("k", "UNFAVORITE");
        verify(postStatService, never()).record(eq("k"), anyString());
        verify(interestService).accumulateFromEvent(any());
    }

    @Test
    void favorite_routesToRecord_notCancel() {
        consumer.onMessage(new FeedBehaviorEvent("k", "FAVORITE", null, null, "1", null, null));

        verify(postStatService).record("k", "FAVORITE");
        verify(postStatService, never()).cancel(anyString(), anyString());
        verify(interestService).accumulateFromEvent(any());
    }
}
