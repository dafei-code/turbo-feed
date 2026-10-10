package com.turbofeed.feedengine.recall;

import com.turbofeed.feedengine.interest.InterestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户向量来源（model 模式）：用 {@link TagCooccurrenceTrainer} 内存中的 tag 向量，
 * 对用户兴趣画像（长期+短期）按权重聚合出用户向量——与内容向量同空间，余弦即契合度。
 *
 * <p>不走 KV 存用户向量：用户向量随兴趣实时变，聚合成本低（仅兴趣标签数个），内存聚合比
 * 每次请求读 KV 更快、更新更及时。tag 向量本身由训练器写 KV 持久化（供 item 向量与跨进程复用）。</p>
 *
 * <p>与默认 {@link InterestUserEmbeddingSource}（hash 占位）互斥：本类在 {@code mode=model} 时
 * 以 {@code @Primary} 生效。无 tag 向量/空画像 → {@code null}（退回规则召回）。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
@Primary
@ConditionalOnProperty(name = "turbofeed.feed.recall.vector.mode", havingValue = "model")
public class RedisUserEmbeddingSource implements UserEmbeddingSource {

    private static final Logger log = LoggerFactory.getLogger(RedisUserEmbeddingSource.class);

    private final TagCooccurrenceTrainer trainer;
    private final InterestService interestService;
    private final int topN;

    public RedisUserEmbeddingSource(TagCooccurrenceTrainer trainer,
                                     InterestService interestService,
                                     @Value("${turbofeed.feed.recall.vector.user-tags:30}") int topN) {
        this.trainer = trainer;
        this.interestService = interestService;
        this.topN = topN;
    }

    @Override
    public double[] embeddingOf(String userId) {
        if (userId == null || !trainer.hasVectors()) {
            return null;
        }
        try {
            Map<String, Double> longTerm = interestService.weightedTags(userId, topN);
            Map<String, Double> shortTerm = interestService.shortTermTags(userId, topN);
            if (longTerm.isEmpty() && shortTerm.isEmpty()) {
                return null; // 冷启动：无画像则无向量
            }
            Map<String, Double> merged = new LinkedHashMap<>(longTerm);
            for (Map.Entry<String, Double> e : shortTerm.entrySet()) {
                merged.merge(e.getKey(), e.getValue(), Double::sum);
            }
            return trainer.userVector(merged);
        } catch (Exception e) {
            log.warn("用户向量聚合失败（fail-open，退回规则召回）: userId={}, {}", userId, e.getMessage());
            return null;
        }
    }
}
