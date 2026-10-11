package com.turbofeed.feedengine.recall;

import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.recall.EmbeddingCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量召回通道默认实现：余弦相似度 TopN（抖音式双塔召回的本地可运行版）。
 *
 * <p>两种检索路径，由 {@code turbofeed.feed.recall.vector.mode} 切换：
 * <ul>
 *   <li><b>model</b>：对候选集（流量池全量解析的 {@link FeedItemView}）逐条
 *       {@link TagEmbeddingService#cosine} 暴力扫描，O(候选数) 内积，数千级成本可接受。</li>
 *   <li><b>ann</b>（路线 A，抖音式工业版）：不遍历候选，直接对 Redis Stack 的 HNSW 向量索引
 *       做 KNN 查询（亚线性），返回 TopK 后由 hash 中 payload 重建 {@link FeedItemView}。
 *       需 Redis Stack / RediSearch 模块；缺失/异常 fail-open 退回规则召回。</li>
 * </ul>
 * 用户向量来自 {@link UserEmbeddingSource}（兴趣画像编码 / A1 KV），内容向量来源随模式不同。</p>
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
    private final VectorIndexWriter vectorIndexWriter;
    private final String mode;

    public TagVectorRecallChannel(UserEmbeddingSource userEmbeddingSource,
                                  ItemEmbeddingSource itemEmbeddingSource,
                                  VectorIndexWriter vectorIndexWriter,
                                  @Value("${turbofeed.feed.recall.vector.mode:hash}") String mode) {
        this.userEmbeddingSource = userEmbeddingSource;
        this.itemEmbeddingSource = itemEmbeddingSource;
        this.vectorIndexWriter = vectorIndexWriter;
        this.mode = mode;
    }

    @Override
    public List<FeedItemView> recall(String userId, List<FeedItemView> candidates, int topN) {
        if (userId == null || topN <= 0) {
            return List.of();
        }
        if ("ann".equalsIgnoreCase(mode)) {
            return annRecall(userId, topN);
        }
        return bruteForceRecall(userId, candidates, topN);
    }

    /** 路线 A：HNSW 索引 KNN 查询（亚线性，不遍历候选集）。 */
    private List<FeedItemView> annRecall(String userId, int topN) {
        try {
            double[] userVec = userEmbeddingSource.embeddingOf(userId);
            if (userVec == null) {
                return List.of(); // 冷启动：无用户向量 → 不做向量召回
            }
            byte[] q = EmbeddingCodec.toFloat32Bytes(userVec);
            // 多取一倍做去重/下游融合余量
            List<String> keys = vectorIndexWriter.knnKeys(q, topN * 2);
            List<FeedItemView> out = new ArrayList<>(topN);
            for (String k : keys) {
                FeedItemView v = vectorIndexWriter.readView(k);
                if (v != null) {
                    out.add(v);
                }
                if (out.size() >= topN) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("HNSW 向量召回失败（fail-open，退回规则召回）: userId={}, {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** model 模式：候选集暴力余弦 TopN（O(候选数)）。 */
    private List<FeedItemView> bruteForceRecall(String userId, List<FeedItemView> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
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
