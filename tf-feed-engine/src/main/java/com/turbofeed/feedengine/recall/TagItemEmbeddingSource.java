package com.turbofeed.feedengine.recall;

import com.turbofeed.shared.model.FeedItemView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 内容向量来源默认实现：用内容标签经 {@link TagEmbeddingService} 编码（等权）。
 *
 * <p>与 {@link InterestUserEmbeddingSource} 共用同一编码器 → 用户向量与内容向量处于同一空间，
 * 余弦相似度即"语义契合度"。内容标签来自 {@link FeedItemView#tags()}。</p>
 *
 * <p>生产缝：真实双塔内容向量应离线预计算后写入 KV（避免每次请求现算），
 * 届时用"读 {@code tf:emb:item:{timelineKey}}"的实现替换本类即可。</p>
 */
@Service
public class TagItemEmbeddingSource implements ItemEmbeddingSource {

    @Override
    public double[] embeddingOf(FeedItemView item) {
        if (item == null) {
            return null;
        }
        List<String> tags = item.tags();
        if (tags == null || tags.isEmpty()) {
            return null; // 无标签内容无法向量化
        }
        return TagEmbeddingService.embed(tags);
    }
}
