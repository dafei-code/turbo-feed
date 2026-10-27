package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.RECALL_OVERFETCH;
import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdFromKey;
import static com.turbofeed.feedengine.timeline.TimelineSupport.recencyOf;

/**
 * 兴趣召回（抖音式多路召回的第三路）。
 *
 * <p>按用户 TopN 兴趣标签经「标签→内容」索引取候选；只排除热点槽位（hotKeys），<b>不排除流量池候选</b>——
 * 召回真正想捞的恰是"在池里但排不进当前页"的同类好内容，重复交给池解析时按 interestKeys 去重。
 * 个性化位内部仍按精排分排序。原内联逻辑（PRE_POOL 段）逐行迁移。</p>
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class InterestRecallChannel implements RecallChannel {

    private final InterestService interestService;
    private final RecallSupport recallSupport;

    @Override
    public RecallType type() {
        return RecallType.INTEREST;
    }

    @Override
    public RecallPhase phase() {
        return RecallPhase.PRE_POOL;
    }

    @Override
    public List<ScoredItem> recall(RecallContext ctx, int slots) {
        if (slots <= 0) {
            return List.of();
        }
        // 过量取：过滤掉与热点重复的后还能填满槽位
        List<String> recalled = interestService.recallTimelineKeys(ctx.userId, slots * RECALL_OVERFETCH + 4);
        List<ScoredItem> part = new ArrayList<>();
        Set<String> interestKeys = ctx.interestKeys;
        Set<String> hotKeys = ctx.hotKeys;
        for (String tk : recalled) {
            if (part.size() >= slots) {
                break;
            }
            if (tk == null || !interestKeys.add(tk) || hotKeys.contains(tk)
                    || ctx.followeeAuthors.contains(authorIdFromKey(tk))) {
                continue;
            }
            FeedItemView it = recallSupport.memberOf(tk);
            if (it == null) {
                continue;
            }
            part.add(new ScoredItem(it, recencyOf(it)));
        }
        // 个性化位内部仍按精排分排序：召回只保证"这类内容进得来"，质量权交给 RankingModel。
        recallSupport.sortByScore(part, ctx);
        return part;
    }
}
