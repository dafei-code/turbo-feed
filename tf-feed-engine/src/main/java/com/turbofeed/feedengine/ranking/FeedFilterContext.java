package com.turbofeed.feedengine.ranking;

import com.turbofeed.feedengine.timeline.TimelineDedup;

import java.util.Set;

/**
 * 推荐过滤链在一次 {@code recommend} 调用内的共享上下文。
 *
 * <p>只包含「全部规则都需要的只读 / 状态化输入」：审核拦截集（内容 / 作者）与整页去重实例。
 * 规则本身无状态（除去重这种必然带状态的），因此上下文在一次请求内构造一次、链内所有规则共享。</p>
 */
public record FeedFilterContext(
        /** 被审核信号拦截的内容 timetalKey 集合（INTERCEPT/MONITOR）。 */
        Set<String> blockedPosts,
        /** 被审核信号拦截 / 低健康分 / 封禁的作者 id 集合。 */
        Set<String> blockedAuthors,
        /** 整页去重实例（状态化：{@code accept} 首次 true、重复 false）。 */
        TimelineDedup dedup) {
}
