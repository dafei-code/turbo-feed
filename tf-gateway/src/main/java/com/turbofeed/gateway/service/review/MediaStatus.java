package com.turbofeed.gateway.service.review;

/**
 * 媒体审核状态（State 模式的状态集合）。
 *
 * <p>UGC 内容面向公域展示，上传后必须经审核流转：</p>
 * <ul>
 *   <li>{@link #PENDING} —— 已受理、待审核（上传成功即进入）</li>
 *   <li>{@link #APPROVED} —— 审核通过，对前端可见</li>
 *   <li>{@link #REJECTED} —— 审核驳回，不对前端展示</li>
 * </ul>
 *
 * <p>合法转换由 {@link MediaReviewService#review} 校验：
 * 仅 PENDING 可转 APPROVED / REJECTED，终态不可再转换。</p>
 */
public enum MediaStatus {
    PENDING,
    APPROVED,
    REJECTED
}
