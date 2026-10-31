package com.turbofeed.gateway.service.notify;

import java.time.Instant;

/**
 * 通知持久化实体（{@code user_notification} 表，分片键 {@code user_id}，HASH_MOD 128）。
 *
 * <p>写模型（OLTP）：每条通知一行，按接收方 {@code user_id} 分片，保证「某用户的收件箱」单分片命中。
 * 读模型（Redis）由 {@link NotificationGenerator} 双写维护：收件箱 ZSET {@code tf:notify:{userId}}
 * （score = createdAtMs, member = notifyId）+ 未读计数 {@code tf:notify:unread:{userId}}。</p>
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
