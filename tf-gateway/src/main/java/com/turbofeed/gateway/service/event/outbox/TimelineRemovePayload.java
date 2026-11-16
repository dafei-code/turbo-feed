package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 事件体：帖子移出公域。
 *
 * <p><b>PR-D3</b>：{@code occurredAt} 是下架事件产生（enqueue）时刻。引擎侧把它写成墓碑
 * {@code tf:timeline:tombstone:{timelineKey}}，用于拦截之后重投的、更早产生的 APPEND。
 * 存量事件行没有该字段 → 反序列化为 {@code null} → 不写墓碑、也不参与防旧比对，
 * 保证历史行为零回归。</p>
 *
 * @param timelineKey 帖身份（有 postId 用 postId，历史单图数据回退 mediaId）——
 *                    必须与 {@code append} 时用的键一致，否则引擎反查索引对不上，下架静默失效。
 * @param occurredAt  事件产生时刻（enqueue 时刻）；可空（存量事件行）
 * @param traceId     触发本次下架的请求链路 id（M4，可空），随发件箱 payload 落库，
 *                    保证中继重投仍能恢复原链路；存量事件行无此字段，按 {@code null} 容忍。
 */
public record TimelineRemovePayload(
        @JsonProperty("timelineKey") String timelineKey,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("traceId") String traceId) {

    /** 本字段引入前的 1 参形态：存量调用点无需改动。 */
    public TimelineRemovePayload(String timelineKey) {
        this(timelineKey, null, null);
    }

    /** traceId 引入后的 2 参形态（PR-D3 之前的生产形态）：时刻未知，按存量兼容。 */
    public TimelineRemovePayload(String timelineKey, String traceId) {
        this(timelineKey, null, traceId);
    }
}
