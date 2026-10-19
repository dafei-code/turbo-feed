package com.turbofeed.counter.redis;

import com.turbofeed.counter.config.CounterProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis L2 连接装配（opt-in，{@code turbofeed.counter.redis.enabled=true} 时建 RedissonClient）。
 *
 * <p>默认关：starter 的 RedissonAutoConfiguration 已在 yml 排除，本 Bean 也不创建，
 * 本地无 Redis 时 tf-counter 仍以 L1 + L3 运行。启用时按 {@code turbofeed.counter.redis.*}
 * 连接单节点；空口令不设置（避免 Redisson 误连带密集群）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "turbofeed.counter.redis.enabled", havingValue = "true")
public class CounterRedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(CounterProperties props) {
        Config c = new Config();
        String pwd = props.getRedis().getPassword();
        c.useSingleServer()
                .setAddress(props.getRedis().getAddresses())
                .setPassword(pwd == null || pwd.isBlank() ? null : pwd);
        return Redisson.create(c);
    }
}
