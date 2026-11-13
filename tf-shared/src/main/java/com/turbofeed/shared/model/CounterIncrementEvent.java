package com.turbofeed.shared.model;

/**
 * 计数增量事件（网关 → tf-counter 的跨服务契约）。
 *
 * <p>语义：对某帖 {@code postId} 的某类互动 {@code type}（{@code LIKE / COMMENT / SHARE}）
 * 发生一次状态变化，计数增减 {@code delta}（{@code +1}=新增，{@code -1}=回退）。
 * 只携带「增量」不携带「绝对值」，与网关实时计数源（Redis 64 槽 Set）解耦——
 * tf-counter 自行聚合、批量落库，保证最终一致，绝不在写路径上抢 Redis 的权威计数。</p>
 *
 * <p>JSON 序列化依赖 record 组件名（postId/type/delta），与下游字段名一致；
 * 本模块零第三方依赖，故不加 {@code @JsonProperty}——Jackson 2.12+ 通过
 * {@code getRecordComponents()} 取组件名，<b>不依赖</b> {@code -parameters}
 * （2026-10-26 实测订正，详见 tf-shared pom 注释）。</p>
 *
 * <p><b>traceId 走消息体而非 MQ 属性</b>：本事件同时被 MQ（{@code CounterMqConsumer}）与
 * HTTP 兜底（{@code HttpCounterEventPublisher} → {@code CounterIncrController}）承载，
 * 只有消息体字段能在两条通道上一致透传，且能被 Outbox 重投 / 对账 SQL 直接查到。</p>
 *
 * @param traceId 产生本次增量的请求链路 id（M4，纯诊断、可空）。新增可空字段按
 *                {@link ContractVersion} 规则不升版本；消费侧必须容忍 {@code null}
 */
public record CounterIncrementEvent(String postId, String type, int delta, String traceId) {

    /** 本字段引入前的 3 参形态：保留签名，避免生产点与测试被迫同步改。 */
    public CounterIncrementEvent(String postId, String type, int delta) {
        this(postId, type, delta, null);
    }

    /** 生产侧补链路 id；传 {@code null} 表示非请求线程。 */
    public CounterIncrementEvent withTraceId(String traceId) {
        return new CounterIncrementEvent(postId, type, delta, traceId);
    }
}
