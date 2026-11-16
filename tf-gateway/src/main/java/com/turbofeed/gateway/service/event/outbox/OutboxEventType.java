package com.turbofeed.gateway.service.event.outbox;

/**
 * 发件箱事件类型（{@code outbox_event.event_type}）。
 *
 * <p><b>为什么要显式枚举而不是随便写字符串</b>：中继按类型分发投递逻辑，字符串写错
 * 只会在运行时抛「未知事件类型」，而枚举把「有哪些事件」变成装配期事实。</p>
 *
 * <p><b>PR-C3 订正</b>：本类旧注释声称「漏在 {@code OutboxService#dispatch} 补分发分支就编译不过」，
 * 这是错的——原实现是箭头式 <b>switch 语句</b>，Java 17 只对 switch <b>表达式</b> 做穷尽性检查，
 * 语句漏 case 编译照过、运行时静默不投。现在约束由 {@link OutboxHandlerRegistry} 在启动期保证：
 * 每个枚举值必须有且只有一个 {@link OutboxEventHandler} Bean，缺一个即启动失败。</p>
 *
 * <p><b>新增枚举值必须与对应 Handler 同批落地</b>，否则启动期校验会直接拒绝应用启动。</p>
 */
public enum OutboxEventType {

    /** 帖子过审入流：投递到 Feed 引擎时间线（引擎侧幂等，同一帖重复投递先摘旧位置再写新位置）。 */
    TIMELINE_APPEND,

    /** 帖子移出公域（删除 / 下架 / 申诉中暂不可见）。 */
    TIMELINE_REMOVE,

    /**
     * 下架后物理删除对象存储对象（PR-C1/C2）。
     *
     * <p>旧链路在处置事务里同步调 {@code storageClient.delete} 且实现吞异常，MinIO 抖动就留下
     * 永久孤儿对象。改走发件箱后：删除失败留行重试 → DEAD 告警 → Sweeper 重投；
     * 重放必须幂等，故 {@code deleteOrThrow} 把 not-found 视为成功。</p>
     */
    STORAGE_DELETE,

    /**
     * 整帖上传完成、待进入审核（PR-D2）。
     *
     * <p>Outbox 是<b>可靠性层</b>，MQ / 本地事件仍是<b>传输层</b>：Handler 按
     * {@code OutboxChannelResolver} 的通道决策继续投递到原有下游，
     * 消费侧 {@code MediaReviewService#handleUploaded} 的 CAS 幂等无需改动。</p>
     *
     * <p>payload 直接复用 {@code MediaUploadedEvent}——它本来就带 {@code occurredAt}
     * 与 {@code traceId}，不需要再包一层事件体（这是重新评审 §4 的一处事实修正）。</p>
     *
     * <p><b>默认关闭</b>：{@code turbofeed.outbox.media-uploaded.enabled=false} 时上传链路
     * 仍走原 {@code MediaEventPublisher} 直发；开启后只写 Outbox，不双跑。</p>
     */
    MEDIA_UPLOADED
}
