package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.FOLLOW_PER_AUTHOR;
import static com.turbofeed.feedengine.timeline.TimelineSupport.recencyOf;

/**
 * 关注流召回（抖音式「关注流 / 社交分发」G6，推荐消费侧）。
 *
 * <p>读 tf:follow:{userId} 社交图，把关注作者的近期已审内容混入发现流。它是流量池之外的社交分发通路，
 * 单独占 follow-recall-ratio 槽位；仅在第 0 页注入（page==0 门控在 pipeline 的 slots 计算里）。
 * 原内联逻辑（POST_POOL 段）逐行迁移。</p>
 */
@Component
@Order(4)
@RequiredArgsConstructor
@Slf4j
public class FollowRecallChannel implements RecallChannel {

    private final RecallSupport recallSupport;

    /** 大 V 每位作者的单页取条数：默认 1，防高频发帖霸占关注流槽位。 */
    @Value("${turbofeed.feed.follow.bigv-per-author-cap:1}")
    private int bigvPerAuthorCap;

    @Override
    public RecallType type() {
        return RecallType.FOLLOW;
    }

    @Override
    public RecallPhase phase() {
        return RecallPhase.POST_POOL;
    }

    @Override
    public List<ScoredItem> recall(RecallContext ctx, int slots) {
        if (slots <= 0) {
            return List.of();
        }
        List<ScoredItem> part = new ArrayList<>();
        Set<String> followKeys = ctx.followKeys;
        for (String aid : ctx.followeeAuthors) {
            if (part.size() >= slots) {
                break;
            }
            // P0 动态配额：大 V 默认每人 1 条，普通作者仍 3 条；ctx.bigVAuthors 为空即完全退化为原行为。
            int cap = ctx.bigVAuthors.contains(aid) ? Math.max(1, bigvPerAuthorCap) : FOLLOW_PER_AUTHOR;
            // P1 分片读：按 viewerId 选分片；shards=1 时等价于原单 key 读取。
            for (FeedItemView it : recallSupport.authorRecentItems(aid, ctx.userId, cap, ctx.cursor)) {
                if (part.size() >= slots) {
                    break;
                }
                String tk = it == null ? null : it.timelineKey();
                if (tk == null || !followKeys.add(tk) || ctx.hotKeys.contains(tk)
                        || ctx.interestKeys.contains(tk) || ctx.vectorKeys.contains(tk)) {
                    continue;
                }
                part.add(new ScoredItem(it, recencyOf(it)));
            }
        }
        // 社交位内部仍按精排分排序（召回只保证"进得来"，排序权交给 RankingModel）。
        recallSupport.sortByScore(part, ctx);
        return part;
    }
}
