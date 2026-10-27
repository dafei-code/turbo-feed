package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.turbofeed.shared.model.FeedItemView;

import java.util.List;

/**
 * 推荐流分页视图（keyset 游标，复用评论 {@code CommentPage} 模式）。
 *
 * <p>{@code /internal/feed/recommended} 与对外 {@code /api/v1/feed/recommended} 现在统一返回本视图：
 * {@link #items()} 为当前页内容，{@link #nextCursor()} 为下一次请求的游标（空串 = 已到末页），
 * {@link #hasMore()} 为本页是否填满（近似"是否还有下一页"），
 * {@link #page()} 为本页的<b>绝对翻页序号</b>（0 起，keyset 模式下由游标 depth 续传累加），
 * 供曝光特征（A1 训练样本）定位"第几页"——否则所有续拉页会被记成第 0 页。</p>
 *
 * <p><b>序列化</b>：项目编译未开启 {@code -parameters}，record 的 JSON 字段名需显式
 * {@link JsonProperty} 绑定，保证网关侧反序列化稳定（与 {@code MediaItem} 同一约定）。</p>
 */
public record FeedRecommendedPage(
        @JsonProperty("items") List<FeedItemView> items,
        @JsonProperty("nextCursor") String nextCursor,
        @JsonProperty("hasMore") boolean hasMore,
        @JsonProperty("page") int page) {
}
