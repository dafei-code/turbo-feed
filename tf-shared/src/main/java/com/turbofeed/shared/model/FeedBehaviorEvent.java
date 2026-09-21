package com.turbofeed.shared.model;

/**
 * 行为埋点事件（网关 → 引擎；经 RocketMQ 或同步 HTTP 承载）。
 *
 * <p><b>零 Jackson 注解</b>：依赖 tf-shared 模块单独开启的 {@code -parameters} 编译参数做反序列化
 * （与 {@link FeedItemView} / {@link FeedTimelineEvent} 同机制），保持本模块零三方依赖定位。</p>
 *
 * <p><b>刻意不用 is/get 前缀方法</b>：record 上任何 is/get 前缀方法会被 Jackson 当属性序列化，
 * 污染消息体（与 {@code FeedTimelineEvent} 同款陷阱）。</p>
 *
 * @param timelineKey 帖身份（{@code postId}；与 {@link FeedItemView#timelineKey()} 口径一致）
 * @param type        行为类型：{@code IMPRESSION}（曝光）/ {@code PLAY_COMPLETE}（完播）/
 *                   {@code LIKE}（点赞）/ {@code COMMENT}（评论）/ {@code SHARE}（分享）/
 *                   {@code DISLIKE}（不感兴趣/负向）
 */
public record FeedBehaviorEvent(String timelineKey, String type) {
}
