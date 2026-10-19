package com.turbofeed.gateway.service.comment;

import java.time.Instant;

/**
 * 评论点赞记录（{@code comment_like} 表行模型，按 {@code media_id} 分片）。
 *
 * <p>单一事实源是 Redis 64 槽去重集合（见 {@link CommentLikeService}），本表仅作灾备重建用。
 * 主键 {@code (comment_id, user_id)} 防并发重复 INSERT 产生孤儿备份行（最终一致取舍，见
 * {@link CommentLikeService} 类注释）。</p>
 */
public record CommentLike(
        long id,
        long commentId,
        long userId,
        String mediaId,
        Instant createdAt) {
}
