package com.turbofeed.gateway.service.feed;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 行为埋点上报载体（前端 → 网关 {@code POST /api/feed/behavior}）。
 *
 * <p>{@code postId} 即帖身份，与 {@code FeedItemView#timelineKey()} 口径一致（新内容都有 postId）。
 * {@code type} 取值：{@code IMPRESSION / WATCH / PLAY_COMPLETE / LIKE / COMMENT / SHARE / DISLIKE}；
 * {@code WATCH} 额外携带 {@code watchDuration}（观看秒数）与 {@code mediaDuration}（内容秒数），用于算完播率。
 * 用 {@code @JsonProperty} 显式标注（网关模块未开启 {@code -parameters}），保证反序列化稳健。</p>
 */
public record BehaviorReport(@JsonProperty("postId") String postId,
                             @JsonProperty("type") String type,
                             @JsonProperty("watchDuration") Integer watchDuration,
                             @JsonProperty("mediaDuration") Integer mediaDuration) {
}
