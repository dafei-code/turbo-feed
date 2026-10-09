package com.turbofeed.feedengine.recall;

import com.turbofeed.feedengine.interest.InterestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户向量来源默认实现：用兴趣画像（长期+短期）经 {@link TagEmbeddingService} 编码。
 *
 * <p>长期层定"你是什么样的人"、短期层定"你现在在追什么"——两者按标签合并权重后编码，
 * 用户向量同时携带稳定偏好与突发兴趣，与内容向量（也是标签编码）处于同一空间，
 * 余弦相似度即"内容契合度"。</p>
 *
 * <p>fail-open：任一异常返回 {@code null}（召回通道据此退回规则召回，绝不阻断浏览）。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
public class InterestUserEmbeddingSource implements UserEmbeddingSource {

    private static final Logger log = LoggerFactory.getLogger(InterestUserEmbeddingSource.class);

    private final InterestService interestService;
    private final int topN;

    public InterestUserEmbeddingSource(InterestService interestService,
                                        @Value("${turbofeed.feed.recall.vector.user-tags:30}") int topN) {
        this.interestService = interestService;
        this.topN = topN;
    }

    @Override
    public double[] embeddingOf(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            Map<String, Double> longTerm = interestService.weightedTags(userId, topN);
            Map<String, Double> shortTerm = interestService.shortTermTags(userId, topN);
            if (longTerm.isEmpty() && shortTerm.isEmpty()) {
                return null; // 冷启动：无画像则无向量
            }
            // 合并（短期层叠加权重，已被 InterestService 各自衰减过）
            Map<String, Double> merged = new LinkedHashMap<>(longTerm);
            for (Map.Entry<String, Double> e : shortTerm.entrySet()) {
                merged.merge(e.getKey(), e.getValue(), Double::sum);
            }
            return TagEmbeddingService.embed(merged);
        } catch (Exception e) {
            log.warn("用户向量编码失败（fail-open，退回规则召回）: userId={}, {}", userId, e.getMessage());
            return null;
        }
    }
}
