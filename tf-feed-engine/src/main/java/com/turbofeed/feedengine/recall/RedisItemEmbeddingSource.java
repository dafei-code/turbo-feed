package com.turbofeed.feedengine.recall;

import com.turbofeed.shared.model.FeedItemView;
import com.turbofeed.shared.recall.EmbeddingCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 内容向量来源（model 模式）：读 Redis KV {@code tf:emb:item:{timelineKey}}。
 *
 * <p>向量由 {@link TagCooccurrenceTrainer}（A0）或真实双塔内容塔（A1）离线/近线写入，
 * 引擎只做"读 KV + 余弦"，不承载任何模型推理。KV 缺失/解码失败 → {@code null}
 * （该内容不参与向量召回，fail-open 退回规则召回）。</p>
 *
 * <p>与默认 {@link TagItemEmbeddingSource}（hash 占位）互斥：本类在 {@code mode=model} 时
 * 以 {@code @Primary} 生效，hash 实现自然退居回退/调试用途——默认 {@code mode=hash} 时零回归。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Service
@Primary
@ConditionalOnProperty(name = "turbofeed.feed.recall.vector.mode", havingValue = "model")
public class RedisItemEmbeddingSource implements ItemEmbeddingSource {

    private static final Logger log = LoggerFactory.getLogger(RedisItemEmbeddingSource.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisItemEmbeddingSource(StringRedisTemplate redis,
                                     @Value("${turbofeed.feed.recall.vector.item-prefix:tf:emb:item:}") String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    public double[] embeddingOf(FeedItemView item) {
        if (item == null) {
            return null;
        }
        try {
            String v = redis.opsForValue().get(prefix + item.timelineKey());
            return EmbeddingCodec.decode(v);
        } catch (Exception e) {
            log.warn("内容向量读取失败（fail-open，退回规则召回）: {}", e.getMessage());
            return null;
        }
    }
}
