package com.turbofeed.gateway.service.comment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 评论创建事件（MQ 削峰载体，opt-in）。
 *
 * <p><b>为什么不复用 {@link Comment} 记录</b>：后者含 {@link Instant} 字段，而 RocketMQ-Spring
 * 默认 ObjectMapper 未注册 JavaTimeModule，直接序列化会抛 InvalidDefinitionException。本事件仅用
 * 基础类型（createdAt 以毫秒 epoch 传递），保证 JSON 往返零时间模块依赖、零精度损失，与
 * {@link Comment#createdAt()} 双向无损。</p>
 *
 * <p><b>兼容陷阱（本类与那三个 record 不同）</b>：这是带 {@code @JsonCreator} 的普通类，
 * 不是 record。新增 {@code traceId} 必须同时满足两个方向：新消费者吃旧消息（缺字段 → null），
 * 以及灰度期旧消费者吃新消息（多字段 → 忽略未知属性）。前者由可空 {@code String} 参数保证，
 * 后者靠本类的 {@link JsonIgnoreProperties} 与 Spring 消息转换器的默认容忍口径；
 * 因此构造器参数一律<b>可空</b>，绝不改成基本类型或加非空断言。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommentCreatedEvent {

    private final long commentId;
    private final String mediaId;
    private final long userId;
    private final long rootId;
    private final long parentId;
    private final String content;
    private final int statusCode;
    private final int likeCount;
    private final long createdAtEpochMillis;
    /** 产生本次事件的请求链路 id（M4，纯诊断、可空）；随消息体透传给评论落库消费者。 */
    private final String traceId;

    @JsonCreator
    public CommentCreatedEvent(
            @JsonProperty("commentId") long commentId,
            @JsonProperty("mediaId") String mediaId,
            @JsonProperty("userId") long userId,
            @JsonProperty("rootId") long rootId,
            @JsonProperty("parentId") long parentId,
            @JsonProperty("content") String content,
            @JsonProperty("statusCode") int statusCode,
            @JsonProperty("likeCount") int likeCount,
            @JsonProperty("createdAtEpochMillis") long createdAtEpochMillis,
            @JsonProperty("traceId") String traceId) {
        this.commentId = commentId;
        this.mediaId = mediaId;
        this.userId = userId;
        this.rootId = rootId;
        this.parentId = parentId;
        this.content = content;
        this.statusCode = statusCode;
        this.likeCount = likeCount;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.traceId = traceId;
    }

    /** 由已校验评论构造事件（丢弃瞬时态，只带落库所需字段）；不带链路 id 的旧调用形态。 */
    public static CommentCreatedEvent from(Comment c) {
        return from(c, null);
    }

    /** 由已校验评论构造事件，并带上当前请求的 traceId。 */
    public static CommentCreatedEvent from(Comment c, String traceId) {
        return new CommentCreatedEvent(c.commentId(), c.mediaId(), c.userId(), c.rootId(),
                c.parentId(), c.content(), c.status().code(), c.likeCount(),
                c.createdAt().toEpochMilli(), traceId);
    }

    /** 反序列化为落库用 {@link Comment}（status 经 {@link CommentStatus#fromCode} 还原）。 */
    public Comment toComment() {
        return new Comment(commentId, mediaId, userId, rootId, parentId, content,
                CommentStatus.fromCode(statusCode), likeCount,
                Instant.ofEpochMilli(createdAtEpochMillis));
    }

    public long getCommentId() {
        return commentId;
    }

    public String getMediaId() {
        return mediaId;
    }

    public long getUserId() {
        return userId;
    }

    public long getRootId() {
        return rootId;
    }

    public long getParentId() {
        return parentId;
    }

    public String getContent() {
        return content;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public String getTraceId() {
        return traceId;
    }
}
