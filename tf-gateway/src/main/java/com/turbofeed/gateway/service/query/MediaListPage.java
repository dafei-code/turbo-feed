package com.turbofeed.gateway.service.query;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 「我的内容」分页视图（P1：media/mine keyset 游标分页，复用 {@code CommentPage} / {@code FeedRecommendedPage} 模式）。
 *
 * <p>{@link #items()} 为当前页帖子（已按帖聚合、images 为帖内全部图片），
 * {@link #nextCursor()} 为续拉游标（空串 = 已到末页），{@link #hasMore()} 为本页是否填满
 * （{@code items.size() == limit}，近似"是否还有下一页"）。</p>
 *
 * <p><b>序列化</b>：项目编译未开启 {@code -parameters}，record 字段名需显式 {@link JsonProperty} 绑定，
 * 保证前端反序列化稳定（与 {@code MediaItem} / {@code FeedRecommendedPage} 同一约定）。</p>
 */
public record MediaListPage(
        @JsonProperty("items") List<MediaItem> items,
        @JsonProperty("nextCursor") String nextCursor,
        @JsonProperty("hasMore") boolean hasMore) {
}
