package com.turbofeed.gateway.service.event.outbox;

import com.turbofeed.shared.feature.FeatureFlagKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 发件箱事件的<b>传输通道决策</b>（PR-D5）：把「这个事件该走本地事件还是 RocketMQ」
 * 从分散在各 Handler 里的 {@code if (mqEnabled)} 收口到一处配置。
 *
 * <h3>为什么需要它</h3>
 * <p>Outbox 是可靠性层（事件行 + 重试），MQ 是传输层（跨进程削峰），两者不是二选一。
 * 若每个 Handler 自己读 {@code turbofeed.mq.enabled}，新增事件时就会出现「A 跟总闸、
 * B 忘了跟、C 又加了个私有开关」的分叉；排障时要在两套链路里找线索。这里给一个统一出口：
 * 默认跟随总闸，允许按事件类型单独覆盖（灰度 / 排障用）。</p>
 *
 * <h3>配置口径</h3>
 * <ul>
 *   <li>{@code turbofeed.outbox.channel.default} 为空（默认）→ 跟随
 *       {@code turbofeed.mq.enabled}：false=LOCAL，true=MQ；</li>
 *   <li>显式 {@code local} / {@code mq} → 覆盖全局；</li>
 *   <li>{@code turbofeed.outbox.channel.media-uploaded} 等单类型键 → 只覆盖该类型；</li>
 *   <li>任何其它取值 → <b>启动期 fail-fast</b>，绝不静默回退到 LOCAL（那会把生产 MQ 通道悄悄关掉）。</li>
 * </ul>
 *
 * <p><b>注意</b>：只有确实存在两种传输实现的事件才需要问本类。
 * {@code TIMELINE_APPEND/REMOVE} 已经由 {@code FeedTimelinePublisher} 端口按总闸选择实现，
 * {@code STORAGE_DELETE} 只有本地对象存储 IO，二者不需要再套一层通道判断。</p>
 */
@Component
public class OutboxChannelResolver {

    private static final Logger log = LoggerFactory.getLogger(OutboxChannelResolver.class);

    public enum Channel {
        /** 本进程内事件（Spring ApplicationEventPublisher → @Async ReviewListener）。 */
        LOCAL,
        /** RocketMQ 跨进程投递。 */
        MQ
    }

    private final Channel defaultChannel;
    private final Map<OutboxEventType, Channel> overrides = new EnumMap<>(OutboxEventType.class);

    public OutboxChannelResolver(
            @Value("${" + FeatureFlagKeys.OUTBOX_CHANNEL_DEFAULT + ":}") String defaultRaw,
            @Value("${" + FeatureFlagKeys.OUTBOX_CHANNEL_MEDIA_UPLOADED + ":}") String mediaUploadedRaw,
            @Value("${" + FeatureFlagKeys.MQ_ENABLED + ":false}") boolean mqEnabled) {
        Channel fallback = mqEnabled ? Channel.MQ : Channel.LOCAL;
        this.defaultChannel = parse("default", defaultRaw, fallback);
        Channel perType = parse("media-uploaded", mediaUploadedRaw, null);
        if (perType != null) {
            overrides.put(OutboxEventType.MEDIA_UPLOADED, perType);
        }
        log.info("Outbox 通道决策就绪: default={}, overrides={}", defaultChannel, overrides);
    }

    /** 返回该事件应走的传输通道；未单独配置的类型走全局默认值。 */
    public Channel channelFor(OutboxEventType type) {
        Channel override = overrides.get(type);
        return override == null ? defaultChannel : override;
    }

    private static Channel parse(String key, String raw, Channel fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("local".equals(v)) {
            return Channel.LOCAL;
        }
        if ("mq".equals(v)) {
            return Channel.MQ;
        }
        throw new IllegalStateException("[OutboxChannelResolver] 非法通道值: " + key + "=" + raw
                + "（只允许 local / mq / 留空跟随总闸）");
    }
}
