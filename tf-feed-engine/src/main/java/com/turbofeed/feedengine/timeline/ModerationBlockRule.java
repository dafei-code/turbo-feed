package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.ranking.FeedFilterContext;
import com.turbofeed.feedengine.ranking.FeedFilterRule;
import com.turbofeed.shared.model.FeedItemView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 审核拦截规则：内容处置 INTERCEPT/MONITOR、或作者健康分过低 / 封禁 → 剔除公域发现流
 * （抖音式「审核与推荐解耦」：推荐侧直读 {@code tf:mod:} KV）。
 *
 * <p>复用写路径同一份真值 {@link TimelineSupport#isModerationBlocked}（本类与 {@code TimelineSupport}
 * 同包，可直读包私有静态方法），避免「召回侧一套、写侧一套」的静默分歧。置 {@code @Order(1)} 保证在
 * 去重规则之前求值——被审核拦截的内容不应占用整页去重名额（与旧内联短路语义一致：
 * {@code isModerationBlocked || !dedup.accept} 中前者为真即不调后者）。</p>
 */
@Order(1)
@Component
public class ModerationBlockRule implements FeedFilterRule {

    @Override
    public boolean allow(FeedItemView item, FeedFilterContext ctx) {
        return !TimelineSupport.isModerationBlocked(item, ctx.blockedPosts(), ctx.blockedAuthors());
    }
}
