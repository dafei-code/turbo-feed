package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.turbofeed.gateway.service.event.MediaUploadedEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-D5 通道收口 + PR-D2 MEDIA_UPLOADED Handler 的行为护栏。
 *
 * <p>锁住两条：① 通道决策只在配置里，非法值启动失败、不静默回退；
 * ② MQ 通道不可用时<b>抛异常留行重试</b>，绝不静默降级成本地事件。</p>
 */
class OutboxChannelRoutingTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MediaUploadedEvent event() {
        return new MediaUploadedEvent("post/42/abc", List.of("media/42/a.jpg"),
                List.of("http://cdn/a.jpg"), "42", "caption", "{}", "req-1",
                Instant.parse("2026-11-16T08:00:00Z"));
    }

    // ===== PR-D5：通道解析 =====

    @Test
    void resolver_emptyConfig_followsMqMasterSwitch() {
        assertEquals(OutboxChannelResolver.Channel.LOCAL,
                new OutboxChannelResolver("", "", false).channelFor(OutboxEventType.MEDIA_UPLOADED));
        assertEquals(OutboxChannelResolver.Channel.MQ,
                new OutboxChannelResolver("", "", true).channelFor(OutboxEventType.MEDIA_UPLOADED));
    }

    @Test
    void resolver_explicitDefault_overridesMasterSwitch() {
        assertEquals(OutboxChannelResolver.Channel.MQ,
                new OutboxChannelResolver("mq", "", false).channelFor(OutboxEventType.TIMELINE_APPEND));
        assertEquals(OutboxChannelResolver.Channel.LOCAL,
                new OutboxChannelResolver("local", "", true).channelFor(OutboxEventType.MEDIA_UPLOADED));
    }

    @Test
    void resolver_perTypeOverride_affectsOnlyThatType() {
        OutboxChannelResolver resolver = new OutboxChannelResolver("mq", "local", false);
        assertEquals(OutboxChannelResolver.Channel.LOCAL,
                resolver.channelFor(OutboxEventType.MEDIA_UPLOADED));
        assertEquals(OutboxChannelResolver.Channel.MQ,
                resolver.channelFor(OutboxEventType.TIMELINE_APPEND));
        assertEquals(OutboxChannelResolver.Channel.MQ,
                resolver.channelFor(OutboxEventType.STORAGE_DELETE));
    }

    @Test
    void resolver_illegalValue_failsFastNeverSilentlyFallsBack() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OutboxChannelResolver("rocket", "", false));
        assertTrue(ex.getMessage().contains("[OutboxChannelResolver]"));
    }

    // ===== PR-D2：Handler =====

    @Test
    void handler_localChannel_publishesApplicationEvent() throws Exception {
        ApplicationEventPublisher local = mock(ApplicationEventPublisher.class);
        OutboxChannelResolver resolver = new OutboxChannelResolver("", "local", true);
        MediaUploadedHandler handler = new MediaUploadedHandler(mapper, resolver, local,
                provider(null), "topic-x");

        assertEquals(OutboxEventType.MEDIA_UPLOADED, handler.type());
        handler.handle(mapper.writeValueAsString(event()));

        verify(local).publishEvent(org.mockito.ArgumentMatchers.isA(MediaUploadedEvent.class));
    }

    @Test
    void handler_mqChannel_sendsToRocketMq_notLocal() throws Exception {
        ApplicationEventPublisher local = mock(ApplicationEventPublisher.class);
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        OutboxChannelResolver resolver = new OutboxChannelResolver("", "mq", false);
        MediaUploadedHandler handler = new MediaUploadedHandler(mapper, resolver, local,
                provider(template), "topic-x");

        handler.handle(mapper.writeValueAsString(event()));

        verify(template).convertAndSend(org.mockito.ArgumentMatchers.eq("topic-x"),
                org.mockito.ArgumentMatchers.isA(MediaUploadedEvent.class));
        verify(local, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void handler_mqChannelWithoutTemplate_throws_soOutboxRetries() {
        ApplicationEventPublisher local = mock(ApplicationEventPublisher.class);
        OutboxChannelResolver resolver = new OutboxChannelResolver("", "mq", false);
        MediaUploadedHandler handler = new MediaUploadedHandler(mapper, resolver, local,
                provider(null), "topic-x");

        assertThrows(IllegalStateException.class,
                () -> handler.handle(mapper.writeValueAsString(event())));
    }

    @Test
    void handler_missingPostId_throws_neverMarksSent() {
        ApplicationEventPublisher local = mock(ApplicationEventPublisher.class);
        OutboxChannelResolver resolver = new OutboxChannelResolver("", "local", false);
        MediaUploadedHandler handler = new MediaUploadedHandler(mapper, resolver, local,
                provider(null), "topic-x");

        assertThrows(Exception.class, () -> handler.handle(
                "{\"mediaIds\":[],\"urls\":[],\"userId\":\"42\"}"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RocketMQTemplate> provider(RocketMQTemplate template) {
        ObjectProvider<RocketMQTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(template);
        return p;
    }
}
