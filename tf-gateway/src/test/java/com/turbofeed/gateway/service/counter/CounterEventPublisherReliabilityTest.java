package com.turbofeed.gateway.service.counter;

import com.turbofeed.shared.model.CounterIncrementEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 突发档（优化项①）计数 MQ 通道可靠性单测：纯 Mockito，无 broker / HTTP / Spring 上下文。
 *
 * <p>守住三级兜底：MQ 成功不碰兜底；MQ 失败转 HTTP 直投；HTTP 也失败进有界重试队列；
 * 恢复后重试队列能被消费清空。改造前发送失败只 WARN 就丢，post_counter 会静默少记。</p>
 */
@ExtendWith(MockitoExtension.class)
class CounterEventPublisherReliabilityTest {

    @Mock
    RocketMQTemplate rocketMQTemplate;
    @Mock
    HttpCounterEventPublisher fallback;

    private RocketMqCounterEventPublisher publisher() {
        return new RocketMqCounterEventPublisher(rocketMQTemplate, "turbofeed-counter-incr", fallback);
    }

    private void stubSendSuccess() {
        doAnswer(inv -> null)
                .when(rocketMQTemplate).syncSendOrderly(anyString(), any(CounterIncrementEvent.class), anyString());
    }

    private void stubSendFailure() {
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).syncSendOrderly(anyString(), any(CounterIncrementEvent.class), anyString());
    }

    @Test
    void mqSuccess_doesNotTouchFallbackOrRetryQueue() {
        stubSendSuccess();
        RocketMqCounterEventPublisher p = publisher();

        p.incr("p1", "LIKE", 1);

        verifyNoInteractions(fallback);
        assertEquals(0, p.pendingRetryCount());
    }

    @Test
    void mqFailure_fallsBackToHttpDirect_insteadOfDroppingIncrement() {
        stubSendFailure();
        when(fallback.tryIncr("p1", "LIKE", 1)).thenReturn(true);
        RocketMqCounterEventPublisher p = publisher();

        p.incr("p1", "LIKE", 1);

        verify(fallback).tryIncr("p1", "LIKE", 1);
        assertEquals(0, p.pendingRetryCount(), "HTTP 兜底成功不应再进重试队列");
    }

    @Test
    void bothChannelsFail_incrementEntersRetryQueue() {
        stubSendFailure();
        when(fallback.tryIncr("p1", "LIKE", 1)).thenReturn(false);
        RocketMqCounterEventPublisher p = publisher();

        p.incr("p1", "LIKE", 1);

        assertEquals(1, p.pendingRetryCount());
        verify(fallback, times(1)).tryIncr("p1", "LIKE", 1);
    }

    @Test
    void retryFailedIncrements_drainsQueue_whenHttpRecovers() {
        stubSendFailure();
        when(fallback.tryIncr(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(false)
                .thenReturn(true);
        RocketMqCounterEventPublisher p = publisher();
        p.incr("p1", "LIKE", 1);
        assertEquals(1, p.pendingRetryCount());

        p.retryFailedIncrements();

        assertEquals(0, p.pendingRetryCount(), "HTTP 恢复后重试队列应被清空");
        verify(fallback, times(2)).tryIncr("p1", "LIKE", 1);
    }

    @Test
    void retryFailedIncrements_requeuesEvent_whenHttpStillDown() {
        stubSendFailure();
        when(fallback.tryIncr("p1", "LIKE", 1)).thenReturn(false);
        RocketMqCounterEventPublisher p = publisher();
        p.incr("p1", "LIKE", 1);

        p.retryFailedIncrements();

        assertEquals(1, p.pendingRetryCount(), "仍失败必须放回队尾，不能丢事件");
    }

}
