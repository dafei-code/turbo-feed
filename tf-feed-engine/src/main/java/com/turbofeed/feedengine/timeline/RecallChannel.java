package com.turbofeed.feedengine.timeline;

import java.util.List;

/**
 * 召回通道（抖音式多路召回的一路）。
 *
 * <p>原 {@link RecommendationPipeline#recommend} 内联了 4 路召回（hot/interest/vector/follow），
 * 各自是一段「取候选 + 跨通道去重 + 精排分排序」的循环。P1 把它们抽成独立的 {@code RecallChannel}
 * 实现，由 Spring 按 {@link #type()} / {@link #phase()} 自动收集为 {@code List<RecallChannel>}，
 * 编排顺序与门控仍由 pipeline 控制，行为零变更。</p>
 *
 * <p><b>约定</b>：{@code recall} 只在 {@code slots > 0} 时被调用（门控由 pipeline 的 slots 计算决定）；
 * 通道内部把已占坑的 key 写入 {@link RecallContext} 的共享排除集（hotKeys/interestKeys/vectorKeys/
 * followKeys），供后续通道与流量池解析做跨通道去重。</p>
 */
public interface RecallChannel {

    /** 通道类型（决定终序槽位）。 */
    RecallType type();

    /** 执行阶段（池解析前 / 后）。 */
    RecallPhase phase();

    /**
     * 召回最多 {@code slots} 条内容。
     *
     * @param ctx   本请求上下文（含共享排除集、评分助手、池候选等）
     * @param slots 本通道槽位数（门控已含：{@code <=0} 时 pipeline 不会调用）
     * @return 召回内容（{@link ScoredItem}，按精排分降序）；无候选时为空列表
     */
    List<ScoredItem> recall(RecallContext ctx, int slots);
}
