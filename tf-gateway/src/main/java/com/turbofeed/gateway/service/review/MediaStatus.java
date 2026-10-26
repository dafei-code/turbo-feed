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
 *   <li>{@link #TAKEN_DOWN} —— 发布后被举报 / 复审确认违规，已下架停推</li>
 *   <li>{@link #APPEALING} —— 作者申诉处理中（内容暂不可见，等待人工复核翻案 / 维持）</li>
 * </ul>
 *
 * <p><b>合法转换的唯一事实源是 {@link MediaStatusMachine}</b>（M1 / 3.3）：新增状态或新增流转
 * 只需改那张表，业务代码不写 {@code cur != X}。本枚举只描述「状态有哪些」，不描述「谁能转到谁」——
 * 此前本注释写的是「仅 PENDING 可转 APPROVED / REJECTED，终态不可再转换」，而
 * {@code TAKEN_DOWN → APPEALING}、{@code APPEALING → APPROVED} 早已落地，文档与代码已漂移；
 * 把流转表挪进 {@link MediaStatusMachine} 并由测试覆盖，正是为了避免这类漂移复发。</p>
 *
 * <p>{@link #DELETED} 由独立的删除操作写入（用户删除自己的内容），不属于审核状态机，
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
