package com.turbofeed.feedengine.ranking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 粗排截断过滤器（抖音式「召回 → 粗排 → 精排 → 重排」里的粗排层，G1）。
 *
 * <p><b>它解决什么</b>：召回后候选量可能远大于单页所需，若全部丢进精排（要查 DB 统计 / Redis 健康分，
 * 每条都贵），算力浪费且长尾内容易被一次性淹没。粗排用<b>廉价特征</b>给候选快速打分、
 * 只保留头部进精排——抖音正是靠这层把「千条候选」压成「百条」再精排。</p>
 *
 * <p><b>逐池生效（2026-10-24 语义修正）</b>：本类的作用域是<b>单个流量池的候选集</b>，不是跨池全集。
 * 读路径的分页是「按池权重切片」，若跨池统一取 Top-N 再拿去过滤各池当页切片，两者几乎不相交
 * （全池千条挑几十条 vs 切片只有个位数），开启后单页条数会塌缩——这正是本层被默认关闭期间
 * 未被发现的缺陷。因此：调用方对每个池分别调用，本类只负责「打分 → 降序 → 截断到 keep」。</p>
 *
 * <p><b>零回归默认</b>：{@code preRankKeepRatio <= 0} 时 {@link #isEnabled()} 为 false，
 * {@link #preRank} 返回空列表（语义 =「不裁剪」），读路径行为与改造前完全一致。</p>
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

    /** 粗排全局开关（{@code preRankKeepRatio > 0} 才启用）。 */
    public boolean isEnabled() {
        return props.getPreRankKeepRatio() > 0d;
    }

    /**
     * 某个流量池是否启用粗排：全局开关 + 池候选量阈值
     * （{@link RankingProperties#getPreRankMinCandidates()}）。
     *
     * <p>池太小（低峰 / 小数据）时粗排既省不了算力、又会改变池内顺序，
     * 用阈值挡掉，行为与关闭态完全一致（零变化）。</p>
     */
    public boolean isEnabledFor(int poolCandidateCount) {
        return isEnabled() && poolCandidateCount >= Math.max(props.getPreRankMinCandidates(), 1);
    }

    /**
     * 某池应保留的候选条数：{@code max(limit × preRankMinPages, ceil(池候选量 × ratio))}。
     *
     * <p>下限（页数）是防塌缩保险：分页按池权重切片，若保留数少于该池分摊到的页大小，
     * 切片就取不满、单页条数塌缩。返回值可能大于池候选量，由调用方再取 {@code min}，
     * {@link #preRank} 内部也会自然截断。</p>
     */
    public int keepCount(int limit, int poolCandidateCount) {
        int byRatio = (int) Math.ceil(poolCandidateCount * props.getPreRankKeepRatio());
        int floor = Math.max(limit, 1) * Math.max(props.getPreRankMinPages(), 1);
        return Math.max(floor, byRatio);
    }

    /**
     * 对一批候选做粗排，返回按得分<b>降序</b>的内容身份列表（timelineKey），最长 {@code keep} 条。
     *
     * <p>返回有序列表（而非集合）是因为调用方需要按此顺序<b>重排池内候选</b>——
     * 粗排不只是"留下谁"，还要决定"谁排前面"：分页在重排后的序列上切，
     * 于是"进这一页"的判据从「入流时刻」变成「兴趣匹配 + 新鲜度」。</p>
     *
     * @return 非空 = 粗排后的有序键列表（调用方据此重排 + 截断）；空 = 不裁剪（禁用 / 无候选）
     */
    public List<String> preRank(List<PreRankCandidate> candidates, PreRankContext ctx, int keep) {
        if (!isEnabled() || candidates == null || candidates.isEmpty() || keep <= 0) {
            return List.of(); // 空 = 不裁剪
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (PreRankCandidate c : candidates) {
            if (c == null || c.item() == null) {
                continue;
            }
            PreRankFeatures feats = PreRankContext.matchOf(c.item(), ctx);
            scored.add(new Scored(c.item().timelineKey(), preRankModel.score(feats)));
        }
        // 降序（稳定排序：同分保持原相对次序，即池内原有次序）
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<String> order = new ArrayList<>(Math.min(keep, scored.size()));
        for (int i = 0; i < scored.size() && order.size() < keep; i++) {
            String tk = scored.get(i).key();
            if (tk != null) {
                order.add(tk);
            }
        }
        return order;
    }

    private record Scored(String key, double score) {
    }
}
