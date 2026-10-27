package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.ranking.FeedFilterContext;
import com.turbofeed.feedengine.ranking.FeedFilterRule;
import com.turbofeed.shared.model.FeedItemView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 整页去重规则（G6）：同一 {@code timelineKey} 在一页内只出现一次（多路召回可能命中同一内容）。
 *
 * <p>{@link FeedFilterContext#dedup()} 是状态化的——{@code accept} 首次返回 {@code true}、重复返回
 * {@code false}，因此本规则必须置于拦截规则之后（被拦内容不占去重名额，见 {@link ModerationBlockRule}
 * 的 {@code @Order(1)}）：否则审核已拦截的内容会被登记进去重集合，语义与改造前不符。</p>
 */
@Order(2)
@Component
public class PageDedupRule implements FeedFilterRule {

    @Override
    public boolean allow(FeedItemView item, FeedFilterContext ctx) {
        return ctx.dedup().accept(item);
    }
}
