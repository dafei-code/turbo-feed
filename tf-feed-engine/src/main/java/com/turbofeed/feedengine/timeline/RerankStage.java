package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;

import java.util.List;

/**
 * 重排阶段（抖音式「多样性重排 G8 / 冷启动 G7 / 生态调控 G9」）。
 *
 * <p>{@code rerank} 在本阶段对应的装配点被调用：POOL 作用于池部分，COLD/FINAL 作用于已装配的整页。
 * 返回新列表（可附加 / 重排 / 封顶），不得修改入参列表。各实现 fail-open：失败或关闭时返回原列表。</p>
 */
public interface RerankStage {

    RerankPhase phase();

    List<FeedItemView> rerank(RecallContext ctx, List<FeedItemView> page);
}
