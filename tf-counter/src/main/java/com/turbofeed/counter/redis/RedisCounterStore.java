package com.turbofeed.counter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.CounterView;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis L2 计数缓存（opt-in，{@code turbofeed.counter.redis-enabled=true} 时激活）。
 *
 * <p>以 {@code tf:counter:{postId}} 为 Key 存 {@link CounterView} JSON，
 * 跨 tf-counter 实例共享、减轻 DB L3 读压。本类仅在 Redis 启用时由 Spring 创建，
 * 否则 {@link CounterReadService} 拿到 {@code null} 自动跳过 L2（零回归）。</p>
 *
 * <p><b>门控属性名（M1 / R2 修正）</b>：此前写的是 {@code turbofeed.counter.redis.enabled}，
 * 而 {@code CounterProperties} 的字段 {@code redisEnabled} 与 yml 里写的键都是
 * {@code turbofeed.counter.redis-enabled}——{@code @ConditionalOnProperty} 按字面量匹配、
 * 不做 relaxed binding，于是<b>配置改了但开关没动</b>：把 yml 的 {@code redis-enabled} 设成
 * true 也不会激活本类。现统一为 canonical 名 {@code turbofeed.counter.redis-enabled}。</p>
 */
@Component
@ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.COUNTER_REDIS_ENABLED, havingValue = "true")
public class RedisCounterStore {

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    public RedisCounterStore(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
    }

    public CounterView get(String postId) {
        try {
            String s = redisson.<String>getBucket("tf:counter:" + postId).get();
            if (s == null) {
                return null;
            }
            return objectMapper.readValue(s, CounterView.class);
        } catch (Exception e) {
            return null; // fail-open：Redis 异常退化为 L3
        }
    }

    public void put(CounterView v) {
        try {
            redisson.<String>getBucket("tf:counter:" + v.postId()).set(objectMapper.writeValueAsString(v));
        } catch (Exception e) {
            // fail-open：写缓存失败不影响落库
        }
    }
}
