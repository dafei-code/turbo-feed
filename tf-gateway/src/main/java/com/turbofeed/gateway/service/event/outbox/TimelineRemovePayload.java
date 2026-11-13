package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 事件体：帖子移出公域。
 *
 * @param timelineKey 帖身份（有 postId 用 postId，历史单图数据回退 mediaId）——
 *                    必须与 {@code append} 时用的键一致，否则引擎反查索引对不上，下架静默失效。
 * @param traceId     触发本次下架的请求链路 id（M4，可空），随发件箱 payload 落库，
 *                    保证中继重投仍能恢复原链路；存量事件行无此字段，按 {@code null} 容忍。
 */
public record TimelineRemovePayload(
        @JsonProperty("timelineKey") String timelineKey,
        @JsonProperty("traceId") String traceId) {

    /** 本字段引入前的 1 参形态：存量调用点无需改动。 */
    public TimelineRemovePayload(String timelineKey) {
        this(timelineKey, null);
    }
}
