package com.turbofeed.feedengine.ranking;

import java.util.Map;

/**
 * 用户实时特征画像（抖音式「实时特征流」在推荐主链路的消费形态）。
 *
 * <p><b>它解决什么</b>：抖音的实时特征流把"用户最近几分钟的行为"聚合成在线特征，
 * 喂给排序模型，让推荐能响应<b>即时兴趣漂移</b>（区别于天/周级衰减的长期+短期画像）。
 * 本引擎已有 {@code SessionSequenceService}（session 级最近互动）+ {@code InterestService} 短期层
 * （12h 半衰期 burst），二者都是实时信号的来源——本对象把它们<b>统一聚合</b>成一份
 * 「标签→实时亲和度」画像 + 一个整体活跃强度，供 {@code RankingModel} 一次性消费，
 * 而不是在每条候选上各查一遍序列（更高效、也更贴近"特征流喂模型"的体感）。</p>
 *
 * <p><b>生产接入缝</b>：上线时，把 {@link RealtimeFeatureService#profileOf} 换成
 * "读实时特征存储（Flink 消费 behavior_log 后写入）"即可，本类作为特征契约不变。</p>
 *
 * <p>无 Lombok：纯值对象（Java 17 record 式语义，但用普通 final 类以便零依赖）。</p>
 */
public final class UserRealtimeProfile {

    /** 空画像（冷启动 / 实时特征不可用）：所有信号退化为 0，调用方据此走冷启动排序。 */
    public static final UserRealtimeProfile EMPTY = new UserRealtimeProfile(Map.of(), 0.0);

    /** 标签 → 实时亲和度（session 最近互动 recency 权重 + 短期 burst 权重之和）。 */
    private final Map<String, Double> tagAffinity;
    /** 整体实时活跃强度（各标签亲和度绝对值之和）：可作为"新鲜度 boost"的幅度依据。 */
    private final double intensity;

    public UserRealtimeProfile(Map<String, Double> tagAffinity, double intensity) {
        this.tagAffinity = (tagAffinity == null) ? Map.of() : tagAffinity;
        this.intensity = intensity;
    }

    public Map<String, Double> tagAffinity() {
        return tagAffinity;
    }

    public double intensity() {
        return intensity;
    }

    public boolean isEmpty() {
        return tagAffinity.isEmpty();
    }
}
