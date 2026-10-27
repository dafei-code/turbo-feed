package com.turbofeed.feedengine.ranking;

import com.turbofeed.shared.model.FeedItemView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 推荐过滤规则链（P2-PR06 形式化）。
 *
 * <p>聚合所有 {@link FeedFilterRule}（Spring 按 {@code @Order} 注入有序列表），候选需<b>通过全部规则</b>
 * 才放行。短路：任一规则拦截即停止后续规则——这保证了「被审核拦截的内容不占去重名额」这类顺序依赖
 * （{@link ModerationBlockRule} 先于 {@link PageDedupRule} 被求值）。</p>
 *
 * <p><b>核心收益</b>：过滤维度从「散落各召回插入点的内联 {@code if}」收敛为一组可单测、可声明的规则。
 * 新增维度只需实现 {@link FeedFilterRule} 并注册为 Spring Bean，{@code RecommendationPipeline} 的
 * 插入点无需再改——这正是本次形式化要解决的「改一条过滤要动最热的读代码」痛点。</p>
 */
@Component
public class FeedFilterChain {

    private final List<FeedFilterRule> rules;

    public FeedFilterChain(List<FeedFilterRule> rules) {
        this.rules = rules;
    }

    /** 候选是否通过该链：全部规则 {@code allow} 才放行（任一拦截即短路返回 false）。 */
    public boolean allow(FeedItemView item, FeedFilterContext ctx) {
        for (FeedFilterRule rule : rules) {
            if (!rule.allow(item, ctx)) {
                return false;
            }
        }
        return true;
    }

    /** 链中规则数（观测用）。 */
    public int size() {
        return rules.size();
    }
}
