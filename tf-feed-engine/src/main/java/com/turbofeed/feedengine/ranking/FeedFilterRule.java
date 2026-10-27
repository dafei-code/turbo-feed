package com.turbofeed.feedengine.ranking;

import com.turbofeed.shared.model.FeedItemView;

/**
 * 推荐过滤规则（P2-PR06 形式化）。
 *
 * <p>把原先散落在 {@code RecommendationPipeline#recommend} 各召回插入点的「审核拦截 / 整页去重」
 * 硬编码条件抽成可组合、可单测的规则。链（{@link FeedFilterChain}）的语义是「全部 {@code allow}
 * 才放行」——任一规则返回 {@code false} 即拦截该候选，与旧内联
 * {@code isModerationBlocked || !dedup.accept} 等价。</p>
 *
 * <p><b>扩展性</b>：日后新增过滤维度（如负向标签硬屏蔽、作者单页频次封顶）只需实现本接口并注册为
 * Spring Bean，规则链自动纳入，无需再改 {@code RecommendationPipeline} 的各插入点。</p>
 */
public interface FeedFilterRule {

    /**
     * 判定候选是否通过本规则。
     *
     * @param item 候选内容
     * @param ctx  本次 recommend 的共享上下文（审核拦截集 + 整页去重）
     * @return {@code true}=放行（可进入推荐流）；{@code false}=拦截
     */
    boolean allow(FeedItemView item, FeedFilterContext ctx);
}
