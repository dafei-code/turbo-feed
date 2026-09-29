package com.turbofeed.gateway.service.feed;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 行为埋点上报载体（前端 → 网关 {@code POST /api/feed/behavior}）。
 *
 * <p>{@code postId} 即帖身份，与 {@code FeedItemView#timelineKey()} 口径一致（新内容都有 postId）。
 * {@code type} 取值：{@code IMPRESSION / WATCH / PLAY_COMPLETE / LIKE / COMMENT / SHARE / DISLIKE}；
 * {@code WATCH} 额外携带 {@code watchDuration}（观看秒数）与 {@code mediaDuration}（内容秒数），用于算完播率。
 * 用 {@code @JsonProperty} 显式标注（网关模块未开启 {@code -parameters}），保证反序列化稳健。</p>
 *
 * <p><b>{@code requestId} / {@code position}（M0 行为日志落盘新增）</b>：由客户端在<b>拉取列表时</b>生成
 * requestId（同一次刷新共用），并在上报时带上该条目在列表里的下标 position。
 * 二者是训练样本的必要条件——没有 requestId 无法归组"当次展示了什么"，
 * 没有 position 无法做 position bias 校正。旧端不传时为 {@code null}，服务端按"缺失"处理，
 * 不影响既有统计（{@code PostStatService}）与画像（{@code InterestService}）逻辑。</p>
 */
public record BehaviorReport(@JsonProperty("postId") String postId,
                             @JsonProperty("type") String type,
                             @JsonProperty("watchDuration") Integer watchDuration,
                             @JsonProperty("mediaDuration") Integer mediaDuration,
                             @JsonProperty("requestId") String requestId,
                             @JsonProperty("position") Integer position) {
}
