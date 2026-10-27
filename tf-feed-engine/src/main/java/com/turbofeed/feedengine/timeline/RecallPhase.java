package com.turbofeed.feedengine.timeline;

/**
 * 召回通道在漏斗中的执行阶段。
 *
 * <p><b>PRE_POOL</b>：流量池解析之前执行（热点 / 兴趣——其候选不依赖池解析结果）。
 * <b>POST_POOL</b>：流量池解析之后执行（向量需池全量候选；关注需 followeeAuthors 已就绪）。</p>
 *
 * <p>编排顺序与门控仍由 {@link RecommendationPipeline#recommend} 控制，通道只负责"给定槽位如何从本路取候选"。</p>
 */
public enum RecallPhase {
    PRE_POOL,
    POST_POOL
}
