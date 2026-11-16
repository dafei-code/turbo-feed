package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.turbofeed.gateway.service.query.MediaItem;

import java.time.Instant;

/**
 * 事件体：帖子过审入流所需的全部信息。
 *
 * <p><b>为什么不只存 postId 让中继回查库</b>：存全量条目的代价是多几十字节 JSON，
 * 换来的是「投递时无需再读库」——中继是补偿通道，越不依赖其他系统越可靠
 * （回查意味着 DB 抖动会连带让补偿也失败）。</p>
 *
 * <p><b>为什么显式 {@link JsonProperty}</b>：项目编译未开启 {@code -parameters}，
 * Jackson 无参数名信息时无法反序列化 record 的 canonical 构造器；{@link MediaItem} 同样如此，
 * 两端都显式标注才能保证「写出 → 读回」稳定往返（与 Redis 缓存 MediaItem 的约定一致）。</p>
 *
 * <p><b>PR-D3：{@code occurredAt} 是事件产生（enqueue）时刻，不是投递时刻</b>。
 * DEAD 行被 {@code OutboxDeadLetterSweeper} 重投时，Handler 从本 payload 重新构造
 * {@code FeedTimelineEvent}；若此时用 {@code Instant.now()}，迟到 APPEND 的时间戳会比
 * 同一帖后续的 REMOVE 还新，引擎侧墓碑比对失效、已下架内容被复活。
 * 存量事件行没有该字段 → 反序列化为 {@code null} → 引擎侧按「时刻未知」放行（向后兼容）。</p>
 *
 * @param item      待入流的帖子条目（整帖视图，images 为该帖全部图片）
 * @param poolLevel 信用等级对应的流量池层级
 * @param occurredAt 事件产生时刻（enqueue 时刻）；可空（存量事件行）
 * @param traceId   触发本次入流的请求链路 id（M4，可空）。<b>必须落在发件箱 payload 里</b>：
 *                  {@code OutboxRelay} 补偿重投时是从库里读 JSON 再重新构造
 *                  {@code FeedTimelineEvent} 的，不是把原事件对象原样重发；不落库就意味着
 *                  中继重投的那一跳会丢 id。落库后还能用一条 SQL 查出「这条事件来自哪次请求」。
 */
public record TimelineAppendPayload(
        @JsonProperty("item") MediaItem item,
        @JsonProperty("poolLevel") int poolLevel,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("traceId") String traceId) {

    /** 本字段引入前的 2 参形态：存量调用点 / 测试无需改动，按「无时刻、无链路」处理。 */
    public TimelineAppendPayload(MediaItem item, int poolLevel) {
        this(item, poolLevel, null, null);
    }

    /** traceId 引入后的 3 参形态（PR-D3 之前的生产形态）：时刻未知，按存量兼容。 */
    public TimelineAppendPayload(MediaItem item, int poolLevel, String traceId) {
        this(item, poolLevel, null, traceId);
    }
}
