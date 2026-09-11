package com.turbofeed.gateway.service.comment;

/**
 * 评论状态机。
 *
 * <ul>
 *   <li>{@link #PENDING} —— 已受理、待审核（敏感词 fail-closed 命中时不会落库，故此态主要留给
 *       「灰度词转人工复核」的演进空间）</li>
 *   <li>{@link #APPROVED} —— 审核通过，对前端可见</li>
 *   <li>{@link #REJECTED} —— 审核驳回，不展示</li>
 *   <li>{@link #DELETED} —— 用户主动删除（逻辑删除，保留审计）</li>
 * </ul>
 *
 * <p>与 {@code media.status} 编码区间对齐：0/1/2/3。</p>
 */
public enum CommentStatus {
    PENDING(0),
    APPROVED(1),
    REJECTED(2),
    DELETED(3);

    private final int code;

    CommentStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static CommentStatus fromCode(int code) {
        return switch (code) {
            case 1 -> APPROVED;
            case 2 -> REJECTED;
            case 3 -> DELETED;
            default -> PENDING;
        };
    }
}
