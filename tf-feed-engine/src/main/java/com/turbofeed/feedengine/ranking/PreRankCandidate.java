package com.turbofeed.feedengine.ranking;

import com.turbofeed.shared.model.FeedItemView;

/**
 * 粗排候选（内容 + 入流时刻 score）。与 {@code FeedTimelineStore.ScoredItem} 同构，
 * 但独立成公开类型以便 {@link PreRankFilter}（不同包）持有——避免暴露引擎内部私有 record。
 */
public record PreRankCandidate(FeedItemView item, double recency) {
}
