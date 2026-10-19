package com.turbofeed.gateway.service.comment;

import java.util.List;

/**
 * 评论分页视图（keyset 游标，杜绝 OFFSET 深分页）。
 *
 * <p>前端用 {@link #nextCursor()} 作为下一次请求的 {@code cursor} 参数；空串表示已到末页。
 * {@link #total()} 为该内容下评论总数（来自 Redis {@code INCR}，见
 * {@link CommentCountService}），与列表本身解耦，支持「快速增长的评论」实时计数。</p>
 */
public record CommentPage(
        List<Comment> items,
        String nextCursor,
        long total,
        boolean hasMore) {
}
