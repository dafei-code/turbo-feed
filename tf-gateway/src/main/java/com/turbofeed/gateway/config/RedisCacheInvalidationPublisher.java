package com.turbofeed.gateway.config;

import com.turbofeed.hotspot.cache.CacheInvalidationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis pub/sub 的缓存失效广播发布者（tf-hotspot SDK 的 {@link CacheInvalidationPublisher} 实现）。
 *
 * <p><b>失败语义</b>：{@code convertAndSend} 抛异常直接上抛，由 SDK 捕获并退化为「TTL 兜底」——
 * 本类不吞异常、也不做重试（重试次数由 SDK 的 {@code broadcastRetries} 控制）。Redis 抖动/未部署时，
 * 一致性退回「整段 TTL」，功能不受影响。</p>
 */
@Component
public class RedisCacheInvalidationPublisher implements CacheInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheInvalidationPublisher.class);

    private final StringRedisTemplate redisTemplate;

    public RedisCacheInvalidationPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publish(String channel, String payload) {
        redisTemplate.convertAndSend(channel, payload);
    }
}
