package com.turbofeed.gateway.service.review;

/**
 * 媒体审核状态（State 模式的状态集合）。
 *
 * <p>UGC 内容面向公域展示，上传后必须经审核流转：</p>
 * <ul>
 *   <li>{@link #PENDING} —— 已受理、待审核（上传成功即进入）</li>
 *   <li>{@link #APPROVED} —— 审核通过，对前端可见</li>
 *   <li>{@link #REJECTED} —— 审核驳回，不对前端展示</li>
 *   <li>{@link #DELETED} —— 用户主动删除（逻辑删除，DB 标记，物理对象在对象存储已删）</li>
 * </ul>
 *
 * <p>审核状态合法转换由 {@link MediaReviewService#review} 校验：
 * 仅 PENDING 可转 APPROVED / REJECTED，终态不可再转换。
 * {@link #DELETED} 由独立的删除操作写入（用户删除自己的内容），不属于审核状态机，
 * 仅用于「我的内容」与公域流过滤——删除后内容不再展示、移出公域时间线。</p>
 */
public enum MediaStatus {
    PENDING,
    APPROVED,
    REJECTED,
    DELETED,
    /** 发布后被举报/流量复审确认违规，已下架停推（区别于 REJECTED：发布前就被拦）。 */
    TAKEN_DOWN,
    /** 作者申诉处理中（内容暂不可见，等待人工复核翻案/维持）。 */
    APPEALING
}
