package com.turbofeed.feedengine.ranking;

/**
 * 粗排（pre-rank）的输入特征——刻意只保留<b>廉价</b>信号：
 * recency + 兴趣层匹配（长期 / 短期 / session / 实时）。
 *
 * <p>与 {@link RankingFeatures}（精排，含曝光统计、负反馈、作者健康分）的区别：
 * 粗排跑在召回之后、精排之前，要对<b>海量候选</b>快速截断（千→百），
 * 所以<b>不查 DB 统计、不查 Redis 健康分</b>——那些贵的特征留给精排。
 * 健康分降权是「精细信号」，在精排层施加；粗排只凭「兴趣 + 新鲜度」做粗滤，
 * 二者职责分离，正是抖音式「召回 → 粗排 → 精排 → 重排」分层的标准切法。</p>
 */
public record PreRankFeatures(double recencyMillis,
                              double interestMatch,
                              double shortTermMatch,
                              double sessionMatch,
                              double realtimeMatch) {
}
