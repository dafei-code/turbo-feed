package com.turbofeed.gateway.service.event.outbox;

/**
 * 发件箱事件处理器（PR-C3 分发注册表的契约）。
 *
 * <p><b>为什么取代 {@code OutboxService#dispatch} 里的 switch</b>：原实现是箭头式
 * <b>switch 语句</b>（不是 switch 表达式），Java 17 只对表达式做穷尽性检查，语句漏一个
 * {@code case} 编译照过、运行时静默什么都不做——事件行会一直停在 PENDING 直到判死。
 * 注册表把「枚举值 ↔ 处理器」变成启动期事实：每个 {@link OutboxEventType#values()} 必须在
 * {@link OutboxHandlerRegistry} 里有且只有一个 Handler，缺一个或抢一个都在应用启动时失败。</p>
 *
 * <h3>新增事件类型的唯一动作</h3>
 * <ol>
 *   <li>在 {@link OutboxEventType} 加枚举值；</li>
 *   <li>写一个实现本接口的 {@code @Component}；</li>
 *   <li><b>两者必须同批落地</b>——先加枚举值不加 Handler，启动期校验会直接拒绝启动。
 *       这不是缺陷，是刻意的顺序约束：它让「有事件却没人投」不可能进入运行时。</li>
 * </ol>
 *
 * <p><b>Handler 不负责 traceId</b>：{@link OutboxService#tryDeliver} 会在调用 handle 之前
 * 统一从 payload JSON 的 {@code traceId} 字段恢复 MDC，快路径与中继重投都走同一段。
 * Handler 只管反序列化自己的 payload 并完成副作用。</p>
 *
 * <p><b>异常语义（关键）</b>：{@link #handle} 抛出的任何异常都会被 {@code OutboxService}
 * 捕获并转成退避重试 / DEAD 告警。因此「下游明确返回失败」也必须抛异常，
 * 绝不能吞掉返回，否则 Outbox 会把一次失败投递标成 SENT。</p>
 */
public interface OutboxEventHandler {

    /** 本处理器负责的事件类型（注册表按它建索引，启动时校验唯一性）。 */
    OutboxEventType type();

    /**
     * 处理一条事件的 payload JSON。
     *
     * @param payloadJson 发件箱行里的原始 JSON（存量行可能缺新字段，反序列化要向后兼容）
     * @throws Exception 任何失败都必须上抛，交由发件箱退避重试
     */
    void handle(String payloadJson) throws Exception;
}
