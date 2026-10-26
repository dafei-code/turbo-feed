package com.turbofeed.counter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * tf-counter 运行配置（绑定 {@code turbofeed.counter.*}）。
 *
 * <ul>
 *   <li>{@code flush-interval-ms}：内存增量缓冲定时批量落库间隔（默认 5s，最终一致窗口）。</li>
 *   <li>{@code redis-enabled}：是否启用 Redis L2 读缓存（默认关；本地无 Redis 直接走 DB L3）。</li>
 *   <li>{@code redis-buffer-enabled}：是否启用<b>共享 Redis 缓冲 + ShedLock 选主</b>的集群模式
 *       （默认关）。开启后缓冲从进程内 {@code ConcurrentHashMap} 切换为 Redis，多副本只有一个
 *       leader 落库。<b>必须与 {@code redis-enabled=true} 同时开启</b>。</li>
 * </ul>
 *
 * <p><b>连接参数不在此处（M1 / R2）</b>：Redis 的<b>地址与口令只有一份</b>，由 tf-redis 的
 * {@code turbofeed.redis.*} 统一持有（{@code mode} / {@code cluster-nodes} / {@code spring.data.redis.*}）。
 * 本类过去自带 {@code turbofeed.counter.redis.addresses/password} 私有配置空间，等于同一套 Redis
 * 两套连接参数——切集群时改了 Lettuce 侧、Redisson 侧没改，不报错，只表现成「计数 L2 读不到」
 * 这类静默失效。现由 tf-redis 按统一拓扑装配 {@code RedissonClient} 后注入本服务。</p>
 *
 * <p><b>前提：{@code turbofeed.redis.redisson-enabled=true}</b>——Redisson 客户端由 tf-redis
 * 显式开关装配（默认关，因为 {@code Redisson.create()} 会立即建连、Redis 不可达即启动失败）。
 * 本类两个 Redis 开关置 true 而该开关未开时，上下文创建即因缺少 {@code RedissonClient} 而失败，
 * 属<b>启动期显式报错</b>，不会静默退化。</p>
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
    /** 是否启用共享 Redis 缓冲 + ShedLock 选主（集群模式，默认关 = standalone 进程内缓冲）。 */
    private boolean redisBufferEnabled = false;

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

    public boolean isRedisBufferEnabled() {
        return redisBufferEnabled;
    }

    public void setRedisBufferEnabled(boolean redisBufferEnabled) {
        this.redisBufferEnabled = redisBufferEnabled;
    }
}
