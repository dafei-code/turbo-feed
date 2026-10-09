package com.turbofeed.feedengine.recall;

import com.turbofeed.shared.model.FeedItemView;

import java.util.List;

/**
 * 向量召回通道（抖音式多路召回里的「向量/双塔召回」这一路）。
 *
 * <p>与 {@code FeedTimelineStore} 已有的「热点 / 兴趣 / 流量池」三路并列，作为<b>第四路召回</b>：
 * 按用户向量与候选内容向量的余弦相似度，把"语义相近但未必打同一标签"的内容捞进发现流——
 * 这是规则/标签召回补不到的"隐式兴趣"维度。召回只决定"这类内容进得来"，排序权交给
 * {@code RankingModel}（含作者健康分降权）。</p>
 *
 * <p>fail-open：任一异常返回空列表（退回其余召回路，绝不阻断浏览）。</p>
 */
public interface VectorRecallChannel {

    /**
     * 从候选集中取与用户向量最相似的 TopN 内容（按余弦相似度降序）。
     *
     * @param userId     用户（{@code null} → 空列表，冷启动不做向量召回）
     * @param candidates 召回候选（通常来自流量池全量解析后的内容）
     * @param topN       期望条数上限（≤0 → 空列表）
     * @return 相似内容列表（已按相似度降序、去重）；无用户向量/异常 → 空列表
     */
    List<FeedItemView> recall(String userId, List<FeedItemView> candidates, int topN);
}
