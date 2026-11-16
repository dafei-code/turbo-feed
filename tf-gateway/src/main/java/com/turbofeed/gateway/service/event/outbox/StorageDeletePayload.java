package com.turbofeed.gateway.service.event.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 事件体：下架处置后物理删除对象存储对象（PR-C1/C2）。
 *
 * <p><b>为什么不只存 mediaId</b>：{@code storage} 记录处置时刻的存储实现，供重投时判断对象
 * 是否还该删（例如事后从 minio 切到 local，重投不应删本地磁盘上另一个实现的文件）；
 * {@code occurredAt} 是处置时刻，便于运维对账「这条孤儿对象清了多久」；
 * {@code traceId} 随 payload 落库，保证中继重投仍能恢复原请求链路。</p>
 *
 * <p>存量事件行没有 {@code occurredAt} / {@code traceId} 字段，反序列化为 {@code null}，
 * Handler 只依赖 {@code mediaId}，按 null 容忍即可（与 Timeline payload 同一兼容口径）。</p>
 *
 * @param mediaId    对象名（即存储 key，形如 media/{userId}/{uuid}.{ext}）
 * @param storage    处置时刻的存储实现（minio / local / placeholder）
 * @param occurredAt 处置发生时间；可空（存量行）
 * @param traceId    触发本次处置的请求链路 id；可空
 */
public record StorageDeletePayload(
        @JsonProperty("mediaId") String mediaId,
        @JsonProperty("storage") String storage,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("traceId") String traceId) {

    /** 本字段引入前的 2 参形态：存量调用点 / 测试无需改动。 */
    public StorageDeletePayload(String mediaId, String storage) {
        this(mediaId, storage, null, null);
    }
}
