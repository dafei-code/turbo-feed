package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.turbofeed.gateway.service.query.MediaItem;

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
 * @param item      待入流的帖子条目（整帖视图，images 为该帖全部图片）
 * @param poolLevel 信用等级对应的流量池层级
 */
public record TimelineAppendPayload(
        @JsonProperty("item") MediaItem item,
        @JsonProperty("poolLevel") int poolLevel) {
}
