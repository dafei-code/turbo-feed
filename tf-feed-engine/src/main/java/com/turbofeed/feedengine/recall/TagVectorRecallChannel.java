package com.turbofeed.feedengine.recall;

import com.turbofeed.shared.model.FeedItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量召回通道默认实现：余弦相似度 TopN（抖音式双塔召回的本地可运行版）。
 *
 * <p>用户向量来自 {@link UserEmbeddingSource}（兴趣画像编码），内容向量来自
 * {@link ItemEmbeddingSource}（内容标签编码），二者同空间 → 余弦即契合度。
 * 候选集通常来自流量池全量（已解析的 {@link FeedItemView}），O(候选数) 内积，
 * 单页候选量（数千级）下成本可接受。</p>
 *
 * <p>fail-open：任一异常返回空列表（召回缺失只让个性化变弱，整页仍可浏览）。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class TagVectorRecallChannel implements VectorRecallChannel {

    private static final Logger log = LoggerFactory.getLogger(TagVectorRecallChannel.class);

    private final UserEmbeddingSource userEmbeddingSource;
    private final ItemEmbeddingSource itemEmbeddingSource;

    public TagVectorRecallChannel(UserEmbeddingSource userEmbeddingSource,
                                  ItemEmbeddingSource itemEmbeddingSource) {
        this.userEmbeddingSource = userEmbeddingSource;
        this.itemEmbeddingSource = itemEmbeddingSource;
    }

    @Override
    public List<FeedItemView> recall(String userId, List<FeedItemView> candidates, int topN) {
        if (userId == null || candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }
        try {
            double[] userVec = userEmbeddingSource.embeddingOf(userId);
            if (userVec == null) {
                return List.of(); // 冷启动：无用户向量 → 不做向量召回
            }
            List<Scored> scored = new ArrayList<>(candidates.size());
            for (FeedItemView it : candidates) {
                double[] itemVec = itemEmbeddingSource.embeddingOf(it);
                if (itemVec == null) {
                    continue;
                }
                double sim = TagEmbeddingService.cosine(userVec, itemVec);
                if (sim > 0.0) {
                    scored.add(new Scored(it, sim));
                }
            }
            scored.sort((a, b) -> Double.compare(b.sim, a.sim));
            int keep = Math.min(topN, scored.size());
            List<FeedItemView> out = new ArrayList<>(keep);
            for (int i = 0; i < keep; i++) {
                out.add(scored.get(i).item);
            }
            return out;
        } catch (Exception e) {
            log.warn("向量召回失败（fail-open，退回规则召回）: userId={}, {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** 单条候选的「内容 + 余弦相似度」持有体。 */
    private record Scored(FeedItemView item, double sim) {
    }
}
