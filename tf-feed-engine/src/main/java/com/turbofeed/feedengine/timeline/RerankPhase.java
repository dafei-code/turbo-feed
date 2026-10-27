package com.turbofeed.feedengine.timeline;

/**
 * 重排阶段。
 *
 * <p><b>POOL</b>：池内多样性重排（G8），作用于已转 {@link com.turbofeed.shared.model.FeedItemView} 的池部分。
 * <b>COLD</b>：冷启动探索注入（G7），作用于已装配的整页（追加新鲜内容）。
 * <b>FINAL</b>：整页生态调控（G9），作用于整页（全局作者/标签封顶）。</p>
 */
public enum RerankPhase {
    POOL,
    COLD,
    FINAL
}
