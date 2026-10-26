package com.turbofeed.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Redis 拓扑装配之 <b>Redisson 客户端</b>：与 {@link RedisTopologyConfig} 共用同一套拓扑参数
 * （{@code turbofeed.redis.mode} / {@code spring.data.redis.*}），客户端按各自需要取用。
 *
 * <p><b>为什么放在 tf-redis 而不是让各模块自建（M1 / R2）</b>：Redisson 客户端此前由
 * tf-counter 的 {@code CounterRedisConfig} 私有装配，带着自己的 {@code turbofeed.counter.redis.*}
 * 配置空间（地址 / 口令）。于是同一套 Redis 有了<b>两套连接配置</b>——这正是本项目
 * 「Redis 拓扑两个服务各配一套」的翻版：切集群时改了 Lettuce 侧、Redisson 侧没改，
 * 不会报错，只表现为「计数 L2 读不到」这类静默失效。</p>
 *
 * <p><b>为什么默认不装配（opt-in）</b>：{@code Redisson.create()} 会<b>立即建立连接</b>，
 * Redis 不可达时直接抛异常 → 应用启动失败。而绝大多数服务（网关 / 引擎）只用 Lettuce
 * 的 {@code StringRedisTemplate}，并不需要 Redisson。因此本装配由
 * {@code turbofeed.redis.redisson-enabled=true} 显式开启，未开启时零 Bean、零连接、零影响。</p>
 *
 * <p><b>为什么用 redisson core 而不是 redisson-spring-boot-starter</b>：starter 附带的
 * {@code RedissonAutoConfigurationV2} 在自己的自动配置里就会创建客户端（同样会"没配也连
 * 127.0.0.1:6379"），历史上 tf-counter 需要在 yml 里显式 {@code exclude} 它才能跑。
 * 用 core 后装配时机完全由本类掌控，且避免了 starter 传递进其它模块引发的意外连接。</p>
 *
 * <p><b>口令空值处理</b>：空串一律降级为 {@code null}——Redisson 对空串口令会真的发送
 * {@code AUTH ""}，在未开鉴权的实例上直接报错。</p>
 *
 * <p><b>关闭钩子</b>：{@code destroyMethod = "shutdown"}，随容器优雅释放连接，避免关闭期
 * 出现 "unreleased connections" 告警（与 Lettuce 工厂同一处理口径）。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "turbofeed.redis.redisson-enabled", havingValue = "true")
public class RedissonTopologyConfig {

    /** Redisson 地址 scheme；节点地址统一补齐它（{@code host:port} → {@code redis://host:port}）。 */
    private static final String SCHEME = "redis://";

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "turbofeed.redis.mode", havingValue = "single", matchIfMissing = true)
    public RedissonClient redissonSingleClient(RedisProperties properties) {
        Config c = new Config();
        c.useSingleServer()
                .setAddress(SCHEME + properties.getHost() + ":" + properties.getPort())
                .setPassword(passwordOrNull(properties.getPassword()));
        return Redisson.create(c);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "turbofeed.redis.mode", havingValue = "cluster")
    public RedissonClient redissonClusterClient(RedisProperties properties,
                                                @Value("${turbofeed.redis.cluster-nodes:}") String nodes) {
        Config c = new Config();
        c.useClusterServers()
                .addNodeAddress(withScheme(nodes, "cluster-nodes"))
                .setPassword(passwordOrNull(properties.getPassword()));
        return Redisson.create(c);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "turbofeed.redis.mode", havingValue = "sentinel")
    public RedissonClient redissonSentinelClient(RedisProperties properties,
                                                @Value("${turbofeed.redis.sentinel-master:}") String master,
                                                @Value("${turbofeed.redis.sentinel-nodes:}") String nodes) {
        if (!StringUtils.hasText(master)) {
            // 与 RedisTopologyConfig 同口径：关键配置缺失即启动失败，绝不静默回落单机
            throw new IllegalStateException("turbofeed.redis.mode=sentinel 时必须配置 turbofeed.redis.sentinel-master");
        }
        Config c = new Config();
        c.useSentinelServers()
                .setMasterName(master)
                .addSentinelAddress(withScheme(nodes, "sentinel-nodes"))
                .setPassword(passwordOrNull(properties.getPassword()));
        return Redisson.create(c);
    }

    /**
     * 把 {@code host:port[,host:port]} 规范为 Redisson 可识别的 {@code redis://host:port} 列表。
     *
     * <p>Lettuce 侧接受裸 {@code host:port}（{@link RedisTopologyConfig} 直接给
     * {@code RedisClusterConfiguration}），Redisson 侧必须带 scheme，故在此统一补齐——
     * 两侧读的是<b>同一份</b> {@code turbofeed.redis.cluster-nodes}，只是格式适配不同。</p>
     *
     * @param key 缺失时报进异常的配置键名（{@code cluster-nodes} / {@code sentinel-nodes}）。
     *            与 {@link RedisTopologyConfig#parseNodes} 同口径：<b>报错必须点名具体是哪个键没配</b>，
     *            否则运维只能对着一句「节点地址不能为空」逐个猜。
     */
    private static String[] withScheme(String nodes, String key) {
        if (!StringUtils.hasText(nodes)) {
            throw new IllegalStateException(
                    "turbofeed.redis." + key + " 不能为空（当前 Redis 拓扑不是单机，却没有给节点地址）");
        }
        String[] raw = nodes.split(",");
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            String n = raw[i].trim();
            out[i] = n.startsWith(SCHEME) || n.startsWith("rediss://") ? n : SCHEME + n;
        }
        return out;
    }

    private static String passwordOrNull(String password) {
        return StringUtils.hasText(password) ? password : null;
    }
}
