package com.turbofeed.feedengine.ranking;

import org.springframework.stereotype.Component;

/**
 * 线性粗排（{@link PreRankModel} 默认实现）。
 *
 * <p>粗排的「轻」体现在：只算兴趣层匹配（长期 / 短期 / session / 实时）+ recency，
 * <b>不碰 DB 统计、不碰 Redis 健康分</b>。与精排线性模型同套目标权重，量纲一致，
 * 因此粗排分与精排分可比——粗排截断不会把「本该靠前」的内容误杀。</p>
 *
 * <p>这是抖音式「粗排」的占位实现；生产可换成<b>双塔浅层 / LR</b>（同一 {@link PreRankModel} 插槽），
 * 输入还是 {@link PreRankFeatures}，读路径无需改动。</p>
 */
@Component
public class LinearPreRankModel implements PreRankModel {

    private final RankingProperties props;

    public LinearPreRankModel(RankingProperties props) {
        this.props = props;
    }

    @Override
    public double score(PreRankFeatures f) {
        double window = props.getRecencyWindowMillis();
        double interest = Math.min(f.interestMatch(), props.getInterestScoreCap());
        double shortTerm = Math.min(f.shortTermMatch(), props.getShortTermScoreCap());
        double session = Math.min(f.sessionMatch(), props.getSessionScoreCap());
        double realtime = Math.min(f.realtimeMatch(), props.getRealtimeScoreCap());
        return f.recencyMillis()
                + window * (props.getInterestWeight() * interest
                          + props.getShortTermWeight() * shortTerm
                          + props.getSessionWeight() * session
                          + props.getRealtimeWeight() * realtime);
    }
}
