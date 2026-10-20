package com.turbofeed.counter;

/**
 * 计数维度（与跨服务契约 {@code CounterIncrementEvent#type} 对齐）。
 *
 * <p>序号固定为数组下标：{@code 0=LIKE, 1=COMMENT, 2=SHARE, 3=COMMENT_LIKE}，缓冲与落库按此顺序聚合。</p>
 *
 * <p><b>新增维度只能追加在末尾</b>：已有序号即数组下标与 {@code post_counter} 列的既有约定，
 * 插到中间会让存量缓冲快照与落库列错位。{@code COMMENT_LIKE}（评论点赞）由网关
 * {@code CommentLikeService} 发出，补上它之前该类型会被 {@link #of} 判为未知而静默丢弃。</p>
 */
public enum CounterType {
    LIKE,
    COMMENT,
    SHARE,
    COMMENT_LIKE;

    /** 容错解析：未知类型返回 {@code null}（调用方跳过，不抛异常）。 */
    public static CounterType of(String s) {
        if (s == null) {
            return null;
        }
        try {
            return CounterType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
