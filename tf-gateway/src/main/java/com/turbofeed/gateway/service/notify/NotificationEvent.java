package com.turbofeed.gateway.service.notify;

import java.time.Instant;

/**
 * 通知领域事件（抖音式通知中心，ADR-0009）。
 *
 * <p><b>生产者</b>：各业务方（MediaLikeService / CommentService / FollowService / MediaFavoriteService /
 * MediaReviewService）只构造并调用 {@link NotificationGenerator#generate(NotificationEvent)}，
 * 不直接写 {@code user_notification} 表——保证「单点生成、解耦、统一聚合 / 限流 / 已读语义」。</p>
 *
 * <p><b>eventId</b>：幂等键。{@link NotificationGenerator} 以 {@code SETNX tf:notify:event:{eventId}}
 * 去重（TTL 1 天），保证消费侧「至少一次」下的幂等（尤其 REVIEW_RESULT 必须最终到达）。</p>
 *
 * <p><b>recipientId</b>：接收方（被通知的用户），即 {@code user_notification.user_id} 分片键。
 * 业务方在构造事件时需解析出接收方（如点赞场景 = 内容作者）。{@code summary} 由业务方生成
 * （文案与 i18n 归属业务域），{@code refPayload} 为可选扩展 JSON。</p>
 */
public record NotificationEvent(
        String eventId,
        NotificationType type,
        long recipientId,
        Long actorId,
        String targetType,
        String targetId,
        String summary,
        String refPayload,
        Instant createdAt
) {
}
