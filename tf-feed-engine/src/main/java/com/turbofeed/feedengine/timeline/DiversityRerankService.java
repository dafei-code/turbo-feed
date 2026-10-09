package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.timeline.FeedTimelineStore.ScoredItem;
import com.turbofeed.shared.model.FeedItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文感知重排服务（抖音式重排多样性 G8）。
 *
 * <p><b>对标的是什么</b>：原 {@code diversify} 只做「同一标签在窗口内不重复」的二进制打散，
 * 缺乏「作者去重」与「软标签重叠惩罚」。抖音的重排是<b>上下文感知的 listwise 重排</b>——
 * 在「尽量保持精排顺序」的前提下，用贪心算法对每一步选出的内容施加多样性约束：
 * 同作者在窗口内出现次数越多越扣分、与窗口内已选内容标签重叠越高越扣分。</p>
 *
 * <p><b>算法</b>：维护「最近 window 条」上下文，对剩余候选逐位贪心选最优：
 * <pre>
 *   score(c) = (N - 原序位)                      // 原序越靠前越优（保住精排语义）
 *            - authorPenaltyWeight × 作者在窗口内出现次数
 *            - tagOverlapPenalty   × 与窗口内最大标签 Jaccard 重叠
 * </pre>
 * 这样既保证高分内容最多被推后几名（可解释、不毁分值分布），又把作者霸屏、同质化刷屏压下去。</p>
 *
 * <p><b>fail-open</b>：任何异常 → 返回原序（不丢内容、不阻断浏览）。打不散时（整页一个话题 / 内容极少）
 * 同样回退原序——「保内容」优先于「形式多样性」。</p>
 */
@Component
public class DiversityRerankService {

    private static final Logger log = LoggerFactory.getLogger(DiversityRerankService.class);

    private final DiversityRerankProperties props;

    public DiversityRerankService(DiversityRerankProperties props) {
        this.props = props;
    }

    /**
     * 对按 displayScore 降序的池候选做上下文感知重排。
     *
     * @param ranked 已按 displayScore 降序排好的候选
     * @return 重排后的候选（同集合、同成员，仅相对次序变化）
     */
    public List<ScoredItem> rerank(List<ScoredItem> ranked) {
        if (!props.isEnabled() || ranked == null || ranked.size() <= 2) {
            return ranked;
        }
        try {
            return greedyRerank(ranked);
        } catch (Exception e) {
            log.warn("上下文感知重排异常，回退原序（fail-open）: {}", e.getMessage());
            return ranked;
        }
    }

    private List<ScoredItem> greedyRerank(List<ScoredItem> ranked) {
        int window = Math.max(1, props.getWindow());
        double authorPenalty = props.getAuthorPenaltyWeight();
        double tagPenalty = props.getTagOverlapPenalty();
        int authorCap = Math.max(1, props.getAuthorCapPerWindow());

        Map<ScoredItem, Integer> origIdx = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            origIdx.put(ranked.get(i), i);
        }

        List<ScoredItem> remaining = new ArrayList<>(ranked);
        List<ScoredItem> out = new ArrayList<>(ranked.size());
        List<ScoredItem> recent = new ArrayList<>();        // 最近 window 条已输出内容（上下文）
        Map<String, Integer> authorCountInWindow = new HashMap<>();

        while (!remaining.isEmpty()) {
            ScoredItem best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (ScoredItem c : remaining) {
                double s = (ranked.size() - origIdx.get(c))
                        - authorPenalty * authorRepeatPenalty(c, authorCountInWindow, authorCap)
                        - tagPenalty * tagOverlapWithRecent(c, recent);
                if (s > bestScore) {
                    bestScore = s;
                    best = c;
                }
            }
            remaining.remove(best);
            out.add(best);
            // 更新上下文窗口
            String aid = FeedTimelineStore.authorIdOf(best.item());
            if (aid != null) {
                authorCountInWindow.merge(aid, 1, Integer::sum);
            }
            recent.add(best);
            if (recent.size() > window) {
                ScoredItem evicted = recent.remove(0);
                String evictedAid = FeedTimelineStore.authorIdOf(evicted.item());
                if (evictedAid != null) {
                    authorCountInWindow.merge(evictedAid, -1, Integer::sum);
                    if (authorCountInWindow.get(evictedAid) <= 0) {
                        authorCountInWindow.remove(evictedAid);
                    }
                }
            }
        }
        return out;
    }

    /** 作者重复惩罚：超过 cap 后线性加重（cap 内也给基础惩罚，促使作者分散）。 */
    private double authorRepeatPenalty(ScoredItem c, Map<String, Integer> authorCountInWindow, int authorCap) {
        String aid = FeedTimelineStore.authorIdOf(c.item());
        if (aid == null) {
            return 0d;
        }
        int cnt = authorCountInWindow.getOrDefault(aid, 0);
        if (cnt <= 0) {
            return 0d;
        }
        // cap 内给 1 份基础惩罚，超出部分每多 1 次再加 1 份（更强压制霸屏）。
        return cnt >= authorCap ? (cnt + (cnt - authorCap + 1)) : (double) cnt;
    }

    /** 与窗口内已选内容的最大标签 Jaccard 重叠（0~1）。无标签内容返回 0（不罚）。 */
    private double tagOverlapWithRecent(ScoredItem c, List<ScoredItem> recent) {
        Set<String> tags = tagsOf(c.item());
        if (tags.isEmpty() || recent.isEmpty()) {
            return 0d;
        }
        double maxOverlap = 0d;
        for (ScoredItem r : recent) {
            Set<String> rt = tagsOf(r.item());
            if (rt.isEmpty()) {
                continue;
            }
            int inter = 0;
            for (String t : tags) {
                if (rt.contains(t)) {
                    inter++;
                }
            }
            int union = tags.size() + rt.size() - inter;
            if (union > 0) {
                maxOverlap = Math.max(maxOverlap, (double) inter / union);
            }
        }
        return maxOverlap;
    }

    private static Set<String> tagsOf(FeedItemView it) {
        List<String> tags = it == null ? null : it.tags();
        return tags == null ? Set.of() : new LinkedHashSet<>(tags);
    }
}
