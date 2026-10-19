package com.turbofeed.counter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * tf-counter 运行配置（绑定 {@code turbofeed.counter.*}）。
 *
 * <ul>
 *   <li>{@code flush-interval-ms}：内存增量缓冲定时批量落库间隔（默认 5s，最终一致窗口）。</li>
 *   <li>{@code redis-enabled}：是否启用 Redis L2（默认关；本地无 Redis 直接走 DB L3）。</li>
 *   <li>{@code redis.*}：Redis L2 连接（仅 {@code redis-enabled=true} 时用于建 RedissonClient）。</li>
 * </ul>
 *
 * <p>注意：本服务的「是否启用」由网关侧 {@code turbofeed.counter.enabled} 决定（控制网关是否发增量）；
 * tf-counter 自身始终作为独立进程运行，本类不含自闸开关。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.counter")
public class CounterProperties {

    /** 内存缓冲批量落库间隔（毫秒）。 */
    private long flushIntervalMs = 5000L;
    /** 是否启用 Redis L2 读缓存（默认关）。 */
    private boolean redisEnabled = false;
    /** Redis L2 连接配置（仅 redis-enabled=true 时生效）。 */
    private Redis redis = new Redis();

    public static class Redis {
        private String addresses = "redis://127.0.0.1:6379";
        private String password = "";

        public String getAddresses() {
            return addresses;
        }

        public void setAddresses(String addresses) {
            this.addresses = addresses;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }
}
