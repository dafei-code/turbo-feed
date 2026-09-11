package com.turbofeed.gateway.service.comment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 评论条目（{@code comment} 表行模型 + 读视图）。
 *
 * <p>楼中楼标识约定：</p>
 * <ul>
 *   <li>{@code rootId} —— 该评论所属的根评论 ID。<b>顶层评论</b>的 {@code rootId = 0}；
 *       回复的 {@code rootId} = 其根评论的 ID（始终为最顶层那条）。这样「顶层列表」用
 *       {@code WHERE root_id = 0}、「某根下的所有回复」用 {@code WHERE root_id = R}，
 *       两种查询都走 {@code idx_media_root_created(media_id, root_id, created_at)}。</li>
 *   <li>{@code parentId} —— 直接父评论 ID（顶层为 0；回复为直接父）。仅展示用，不参与主查询。</li>
 * </ul>
 */
public record Comment(
        @JsonProperty("commentId") long commentId,
        @JsonProperty("mediaId") String mediaId,
        @JsonProperty("userId") long userId,
        @JsonProperty("rootId") long rootId,
        @JsonProperty("parentId") long parentId,
        @JsonProperty("content") String content,
        @JsonProperty("status") CommentStatus status,
        @JsonProperty("likeCount") int likeCount,
        @JsonProperty("createdAt") Instant createdAt) {
}
