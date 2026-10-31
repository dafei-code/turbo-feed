package com.turbofeed.gateway.service.notify;

/**
 * 通知类型枚举（与 ADR-0009 一致）。抖音式通知中心至少覆盖以下互动闭环：
 *
 * <ul>
 *   <li>{@code LIKE}         有人赞了你的内容</li>
 *   <li>{@code COMMENT}      有人评论了你的内容</li>
 *   <li>{@code REPLY}        有人回复了你的评论</li>
 *   <li>{@code MENTION}      有人在评论 / @ 中提到了你</li>
 *   <li>{@code FOLLOW}       有人关注了你</li>
 *   <li>{@code FAVORITE}     有人收藏了你的内容</li>
 *   <li>{@code SYSTEM}       系统公告 / 平台消息（广播写全量收件箱）</li>
 *   <li>{@code REVIEW_RESULT} 你的内容审核结果（通过 / 驳回）</li>
 * </ul>
 */
public enum NotificationType {
    LIKE,
    COMMENT,
    REPLY,
    MENTION,
    FOLLOW,
    FAVORITE,
    SYSTEM,
    REVIEW_RESULT
}
