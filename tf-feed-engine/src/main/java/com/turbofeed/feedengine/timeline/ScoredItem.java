package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;

/**
 * 池内重排用的"内容 + 入流时刻(score)"持有体。
 *
 * <p>原是 {@link RecommendationPipeline} 的内部 record；P1 组件化（RecallChannel / RerankStage）
 * 需要跨类复用——召回通道产出 {@code List<ScoredItem>}，重排阶段消费它——故提升为 timeline 包顶层类型。
 * {@code recency} 即时间线 ZSET 的 score（入流时刻毫秒），用于池内按新鲜度重排与精排打分输入。</p>
 */
public record ScoredItem(FeedItemView item, double recency) {
}
