package com.turbofeed.feedengine.ranking;

import com.turbofeed.shared.model.FeedItemView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 粗排截断过滤器（抖音式「召回 → 粗排 → 精排 → 重排」里的粗排层，G1）。
 *
 * <p><b>它解决什么</b>：召回后候选量可能远大于单页所需，若全部丢进精排（要查 DB 统计 / Redis 健康分，
 * 每条都贵），算力浪费且长尾内容易被一次性淹没。粗排用<b>廉价特征</b>给候选快速打分、
 * 只保留 Top-N 进精排——抖音正是靠这层把「千条候选」压成「百条」再精排。</p>
 *
 * <p><b>零回归默认</b>：{@code preRankKeepRatio <= 0} 时 {@link #isEnabled()} 为 false，
 * {@link #preRank} 返回空集合（语义 =「不裁剪」），读路径行为与改造前完全一致。</p>
 *
 * <p><b>fail-open</b>：裁剪是「优选保留」，出错（候选为空 / 计算异常）最多退化为「不裁剪」，
 * 绝不因此丢内容或让推荐页失败。</p>
 */
@Component
public class PreRankFilter {

    private final PreRankModel preRankModel;
    private final RankingProperties props;

    public PreRankFilter(PreRankModel preRankModel, RankingProperties props) {
        this.preRankModel = preRankModel;
        this.props = props;
    }

    /** 是否启用粗排（{@code preRankKeepRatio > 0} 才启用）。 */
    public boolean isEnabled() {
        return props.getPreRankKeepRatio() > 0d;
    }

    /** 启用时建议保留的候选条数（基于单页 limit 的倍数）。 */
    public int keepCount(int limit) {
        return (int) Math.floor(limit * props.getPreRankKeepRatio());
    }

    /**
     * 对一批候选做粗排截断，返回应保留的内容身份集合（timelineKey）。
     *
     * @return 非空 = 应保留的键集合（调用方据此过滤）；空 = 不裁剪（禁用 / 无候选）
     */
    public Set<String> preRank(List<PreRankCandidate> candidates, PreRankContext ctx, int keep) {
        if (!isEnabled() || candidates == null || candidates.isEmpty()) {
            return Set.of(); // 空 = 不裁剪
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (PreRankCandidate c : candidates) {
            if (c == null || c.item() == null) {
                continue;
            }
            PreRankFeatures feats = PreRankContext.matchOf(c.item(), ctx);
            scored.add(new Scored(c, preRankModel.score(feats)));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        Set<String> kept = new LinkedHashSet<>();
        for (int i = 0; i < scored.size() && kept.size() < keep; i++) {
            String tk = scored.get(i).candidate.item().timelineKey();
            if (tk != null) {
                kept.add(tk);
            }
        }
        return kept;
    }

    private record Scored(PreRankCandidate candidate, double score) {
    }
}
