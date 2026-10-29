package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.recall.VectorRecallChannel;
import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.turbofeed.feedengine.timeline.TimelineSupport.RECALL_OVERFETCH;
import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdOf;
import static com.turbofeed.feedengine.timeline.TimelineSupport.recencyOf;

/**
 * 向量召回（抖音式多路召回的第四路）：用户向量 × 内容向量余弦相似度 TopN。
 *
 * <p>与兴趣召回互补——后者是"显式同标签"，本路是"语义相近但未必同标签"的隐式兴趣。
 * 候选来自流量池全量（排除已占热点/兴趣槽位者），过量取后由既有 {@link VectorRecallChannel} 通道
 * 内部按相似度截断——本组件<b>委托</b>该 bean（含 HNSW KNN / 暴力回退与指标装饰），不改其签名与实现。
 * 原内联逻辑（POST_POOL 段）逐行迁移。</p>
 */
@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class VectorFeedRecallChannel implements RecallChannel {

    private final VectorRecallChannel vectorRecallChannel;
    private final RecallSupport recallSupport;

    @Override
    public RecallType type() {
        return RecallType.VECTOR;
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
        List<FeedItemView> candidates = new ArrayList<>();
        for (List<FeedItemView> l : ctx.parsed.values()) {
            for (FeedItemView it : l) {
                String tk = it == null ? null : it.timelineKey();
                if (tk != null && !ctx.hotKeys.contains(tk) && !ctx.interestKeys.contains(tk)
                        && !ctx.cfKeys.contains(tk)) {
                    candidates.add(it);
                }
            }
        }
        List<FeedItemView> recalled = vectorRecallChannel.recall(ctx.userId,
                candidates, slots * RECALL_OVERFETCH + 4);
        List<ScoredItem> part = new ArrayList<>();
        Set<String> vectorKeys = ctx.vectorKeys;
        for (FeedItemView it : recalled) {
            if (part.size() >= slots) {
                break;
            }
            if (it == null) {
                continue;
            }
            String tk = it.timelineKey();
            if (tk == null || !vectorKeys.add(tk) || ctx.hotKeys.contains(tk)
                    || ctx.interestKeys.contains(tk) || ctx.cfKeys.contains(tk)
                    || ctx.followeeAuthors.contains(authorIdOf(it))) {
                continue;
            }
            part.add(new ScoredItem(it, recencyOf(it)));
        }
        // 同个性化位，内部仍按精排分排序（召回只保证"进得来"，排序权交给 RankingModel）。
        recallSupport.sortByScore(part, ctx);
        return part;
    }
}
