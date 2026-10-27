package com.turbofeed.gateway.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.turbofeed.gateway.service.query.MediaItem;

import java.util.List;

/**
 * 推荐流分页视图（网关侧，与引擎 {@code FeedRecommendedPage} 契约对称）。
 *
 * <p>{@code /api/v1/feed/recommended} 现在统一返回本视图（复用评论 {@code CommentPage} 模式）：
 * {@link #items()} 为当前页内容（已由 {@link FeedEngineClient} 从引擎 {@code FeedItemView}
 * 映射为网关 {@link MediaItem} 视图），{@link #nextCursor()} 为续拉游标
 * （空串 = 已到末页），{@link #hasMore()} 为本页是否填满。</p>
 *
 * <p><b>为什么是 {@code List<MediaItem>} 而不是 {@code FeedItemView}</b>：本视图是网关对外
 * （前端 / 下游服务）的展示模型，客户端已注入点赞/评论计数与已赞态（抖音式「计数接卡片」），
 * 这些只存在于网关侧、引擎下发的原始契约里没有，故此处必须是 {@link MediaItem}。</p>
 *
 * <p><b>序列化</b>：项目编译未开启 {@code -parameters}，record 字段名需显式 {@link JsonProperty}
 * 绑定，保证反序列化引擎下发的 JSON 稳定（与 {@code MediaItem} 同一约定）。</p>
 */
public record FeedRecommendedPage(
        @JsonProperty("items") List<MediaItem> items,
        @JsonProperty("nextCursor") String nextCursor,
        @JsonProperty("hasMore") boolean hasMore) {
}
