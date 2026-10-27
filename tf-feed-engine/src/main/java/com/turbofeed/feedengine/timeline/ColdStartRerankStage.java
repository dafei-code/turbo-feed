package com.turbofeed.feedengine.timeline;

import com.turbofeed.shared.model.FeedItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.turbofeed.feedengine.timeline.TimelineSupport.authorIdOf;

/**
 * 冷启动探索池（G7：EE 探索利用）：给新内容 / 低曝光内容固定曝光配额，避免「分数低就永远刷不到」的饿死。
 *
 * <p>从 {@link ColdStartService} 取预算与阈值，经 {@link RecallSupport#collectColdStartCandidates}
 * 取候选，复用整页去重与审核过滤后追加到整页末尾。fail-open：取候选失败 / 关闭 / 预算为 0 → 原页返回。
 * 原内联逻辑（recommend 的冷启动段）逐行迁移。</p>
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class ColdStartRerankStage implements RerankStage {

    private final ColdStartService coldStartService;
    private final RecallSupport recallSupport;

    @Override
    public RerankPhase phase() {
        return RerankPhase.COLD;
    }

    @Override
    public List<FeedItemView> rerank(RecallContext ctx, List<FeedItemView> page) {
        Instant coldThreshold = (ctx.cursor != null)
                ? Instant.ofEpochMilli((long) Math.floor(ctx.cursor.score()))
                : coldStartService.coldThreshold(Instant.now());
        int coldBudget = coldStartService.exploreBudget(ctx.limit);
        if (coldBudget <= 0) {
            return page;
        }
        List<FeedItemView> cold = recallSupport.collectColdStartCandidates(coldBudget, coldThreshold, coldStartService.maxPool());
        if (cold.isEmpty()) {
            return page;
        }
        // 复用整页 seen 集合，与热/关注/兴趣/向量/池统一去重；关注作者内容不在冷启动位重复刷（跨页去重）。
        List<FeedItemView> out = new ArrayList<>(page);
        for (FeedItemView it : cold) {
            if (!recallSupport.passFilters(it, ctx) || ctx.followeeAuthors.contains(authorIdOf(it))) {
                continue;
            }
            out.add(it);
        }
        return out;
    }
}
