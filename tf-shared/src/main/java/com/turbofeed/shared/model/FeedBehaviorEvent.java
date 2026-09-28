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
 * @param timelineKey  帖身份（{@code postId}；与 {@link FeedItemView#timelineKey()} 口径一致）
 * @param type         行为类型：{@code IMPRESSION}（曝光）/ {@code WATCH}（完播上报，携带观看时长）/
 *                     {@code PLAY_COMPLETE}（完播，二进制，兼容旧端）/ {@code LIKE}（点赞）/
 *                     {@code COMMENT}（评论）/ {@code SHARE}（分享）/ {@code DISLIKE}（不感兴趣/负向）
 * @param watchDuration  观看时长（秒）；仅 {@code WATCH} 使用，由客户端上报
 * @param mediaDuration  内容时长（秒）；仅 {@code WATCH} 使用，视频为真实时长、图文为期望驻留时长。
 *                     服务端据 {@code watchDuration / mediaDuration} 算完播率，无上报时为 {@code null}
 * @param userId        行为发起者；用于累积兴趣画像与个性化召回（见引擎 {@code InterestService}）。
 *                     由网关登录态注入（行为上报接口要求 {@code FEED_INTERACT} 权限，必为已登录用户）。
 *                     引擎侧消费时按"非空才累积"处理，匿名埋点（理论不存在）不污染画像。
 */
public record FeedBehaviorEvent(String timelineKey, String type, Integer watchDuration, Integer mediaDuration, String userId) {
}
