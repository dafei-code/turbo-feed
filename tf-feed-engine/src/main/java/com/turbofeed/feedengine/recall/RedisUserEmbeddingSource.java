package com.turbofeed.feedengine.recall;

import com.turbofeed.feedengine.interest.InterestService;
import com.turbofeed.shared.recall.EmbeddingCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户向量来源（model 模式）：优先用 A1 离线双塔训练出的用户向量（KV {@code tf:emb:user:{userId}}），
 * 缺失时退回「兴趣画像 × tag 向量」的内联聚合（A0 行为）。
 *
 * <p><b>两路用户向量</b>：
 * <ul>
 *   <li><b>A1 训练向量（优先）</b>：由 {@code TwoTowerUserTrainer}（网关）离线把用户有序交互序列
 *       池化为用户向量写 KV，序列感知、跨进程复用，优于内联聚合。本类直接读 KV，命中即返回。</li>
 *   <li><b>内联聚合（退回）</b>：用 {@link TagCooccurrenceTrainer} 内存中的 tag 向量，对用户兴趣画像
 *       （长期+短期）按权重聚合——与内容向量同空间，余弦即契合度。A1 未训练/KV 缺失时走此路，零回归。</li>
 * </ul>
 * </p>
 *
 * <p>与默认 {@link InterestUserEmbeddingSource}（hash 占位）互斥：本类在 {@code mode=model} 时
 * 以 {@code @Primary} 生效。无 tag 向量/空画像/KV 全缺 → {@code null}（退回规则召回）。</p>
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
    private final StringRedisTemplate redis;
    private final int topN;
    private final String userPrefix;
    private final int dim;

    public RedisUserEmbeddingSource(TagCooccurrenceTrainer trainer,
                                     InterestService interestService,
                                     StringRedisTemplate redis,
                                     @Value("${turbofeed.feed.recall.vector.user-tags:30}") int topN,
                                     @Value("${turbofeed.feed.recall.vector.user-prefix:tf:emb:user:}") String userPrefix,
                                     @Value("${turbofeed.feed.recall.vector.dim:64}") int dim) {
        this.trainer = trainer;
        this.interestService = interestService;
        this.redis = redis;
        this.topN = topN;
        this.userPrefix = userPrefix;
        this.dim = dim;
    }

    @Override
    public double[] embeddingOf(String userId) {
        if (userId == null) {
            return null;
        }
        // 1) 优先用 A1 离线双塔训练出的用户向量（序列感知，优于内联聚合）
        try {
            double[] trained = EmbeddingCodec.decode(redis.opsForValue().get(userPrefix + userId));
            if (trained != null && trained.length == dim) {
                return trained;
            }
        } catch (Exception ignore) {
            // KV 读失败 → 退回内联聚合
        }
        // 2) 退回现有内联聚合（兴趣标签 × tag 向量），零回归
        if (!trainer.hasVectors()) {
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
