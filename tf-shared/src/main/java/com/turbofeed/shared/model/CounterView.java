package com.turbofeed.shared.model;

/**
 * 聚合计数视图（tf-counter 查询返回，三级读缓存 L1/L2/L3 的统一载体）。
 *
 * <p>计数来自 tf-counter 自有 {@code post_counter} 表（批量落库的<b>最终一致</b>聚合），
 * 与网关实时点赞数（Redis 64 槽 SCARD）口径不同：本视图用于持久查询 / 管理端 / 批量计数，
 * 不替代卡片红心的实时计数。</p>
 *
 * <p><b>维度与 {@code CounterType} 序号一一对应</b>：like / comment / share / commentLike，
 * 新增维度一律追加在末尾（顺序即缓冲数组下标与落库列顺序，中间插入会造成存量数据错位）。</p>
 *
 * <p>JSON 序列化依赖 record 组件名（postId/likeCount/...），与下游字段名一致；
 * 本模块零第三方依赖，不加 {@code @JsonProperty}——Jackson 2.12+ 通过
 * {@code getRecordComponents()} 取组件名，<b>不依赖</b> {@code -parameters}
 * （2026-10-26 实测订正，详见 tf-shared pom 注释）。</p>
 */
public record CounterView(String postId, long likeCount, long commentCount, long shareCount, long commentLikeCount) {

    /** 全零计数（帖尚无任何互动时返回）。 */
    public static CounterView zero(String postId) {
        return new CounterView(postId, 0L, 0L, 0L, 0L);
    }

    /** 在现有视图上叠加一组增量（内存聚合用，不落库）。 */
    public CounterView add(long dLike, long dComment, long dShare, long dCommentLike) {
        return new CounterView(postId, likeCount + dLike, commentCount + dComment,
                shareCount + dShare, commentLikeCount + dCommentLike);
    }
}
