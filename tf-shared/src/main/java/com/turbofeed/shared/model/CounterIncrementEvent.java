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
 */
public record CounterIncrementEvent(String postId, String type, int delta) {
}
