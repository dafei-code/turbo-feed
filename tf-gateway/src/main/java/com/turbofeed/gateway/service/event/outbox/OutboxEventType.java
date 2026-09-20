package com.turbofeed.gateway.service.event.outbox;

/**
 * 发件箱事件类型（{@code outbox_event.event_type}）。
 *
 * <p><b>为什么要显式枚举而不是随便写字符串</b>：中继按类型分发投递逻辑，字符串写错
 * 只会在运行时抛「未知事件类型」，而枚举把「有哪些事件」变成编译期事实——
 * 新增事件必须在这里登记 + 在 {@code OutboxService#dispatch} 补分发分支，漏一处就编译不过。</p>
 */
public enum OutboxEventType {

    /** 帖子过审入流：投递到 Feed 引擎时间线（引擎侧幂等，同一帖重复投递先摘旧位置再写新位置）。 */
    TIMELINE_APPEND,

    /** 帖子移出公域（删除 / 下架 / 申诉中暂不可见）。 */
    TIMELINE_REMOVE
}
