package com.turbofeed.gateway.service.notify;

import java.time.Instant;

/**
 * 通知持久化实体（{@code user_notification} 表，分片键 {@code user_id}，HASH_MOD 128）。
 *
 * <p>写模型（OLTP）：每条通知一行，按接收方 {@code user_id} 分片，保证「某用户的收件箱」单分片命中；
 * 收件箱列表直接查本表。Redis 侧只保留未读计数快路径 {@code tf:notify:unread:{userId}}（DB 回源兜底），
 * 原设计的收件箱 ZSET 因读路径从未使用且无界增长已于 2026-11-21 移除，详见 {@link NotificationGenerator}。</p>
 */
public record Notification(
        long id,
        long userId,
        String type,
        Long actorId,
        String targetType,
        String targetId,
        String summary,
        String refPayload,
        boolean read,
        Instant createdAt,
        long revision
) {
}
