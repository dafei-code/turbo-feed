package com.turbofeed.counter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.shared.model.CounterView;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis L2 计数缓存（opt-in，{@code turbofeed.counter.redis.enabled=true} 时激活）。
 *
 * <p>以 {@code tf:counter:{postId}} 为 Key 存 {@link CounterView} JSON，
 * 跨 tf-counter 实例共享、减轻 DB L3 读压。本类仅在 Redis 启用时由 Spring 创建，
 * 否则 {@link CounterReadService} 拿到 {@code null} 自动跳过 L2（零回归）。</p>
 */
@Component
@ConditionalOnProperty(name = "turbofeed.counter.redis.enabled", havingValue = "true")
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
