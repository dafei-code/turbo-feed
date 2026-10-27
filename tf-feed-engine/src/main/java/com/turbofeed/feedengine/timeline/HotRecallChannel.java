package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.recencyOf;

/**
 * 热点召回（抖音式"热门"独立通路）。
 *
 * <p>热度榜 Top-N → 经反查索引还原条目 → 排除关注作者（跨页去重）→ 占 hotKeys 坑。
 * 它是流量池之外的第二路召回，单独占 hot-recall-ratio 槽位，剩余才归流量池。原内联逻辑（PRE_POOL 段）逐行迁移。</p>
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class HotRecallChannel implements RecallChannel {

    private final HotTimelineKeyCache hotTimelineKeyCache;
    private final PostStatService postStatService;

    @Override
    public RecallType type() {
        return RecallType.HOT;
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
        List<ScoredItem> hot = new ArrayList<>();
        Set<String> hotKeys = ctx.hotKeys;
        for (String tk : hotTimelineKeyCache.getHotKeys(slots,
                () -> postStatService.rawHotTimelineKeys(HotTimelineKeyCache.CAP))) {
            FeedItemView it = ctx.support.memberOf(tk);
            // 关注作者内容只在首屏关注位曝光，热点通道不再重复刷（跨页去重）。
            if (it != null && !ctx.followeeAuthors.contains(authorIdOf(it)) && hotKeys.add(tk)) {
                hot.add(new ScoredItem(it, recencyOf(it)));
            }
        }
        return hot;
    }
}
