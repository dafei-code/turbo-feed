package com.turbofeed.redis;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis 拓扑装配：<b>单机（默认）→ 哨兵 / 集群</b> 的可切换连接工厂。
 *
 * <p><b>为什么从 tf-gateway 下沉到本模块</b>：拓扑配置曾经只存在于 {@code tf-gateway}，
 * 而 {@code tf-feed-engine} 是另一个独立部署单元、同样持有 Redis 读模型（时间线 ZSET）。
 * 结果是：删除单机 Redis 切换集群（changelog 0044）时只改了网关，引擎仍连已下线的
 * {@code localhost:6379}，时间线读写静默失败 → 整个推荐流空白。
 * <b>「两个服务各配一套 Redis」本身就是缺陷</b>，本模块把它收成单一事实源：
 * 属性命名空间 {@code turbofeed.redis.*} 对所有服务一致。</p>
 *
 * <p><b>为什么不直接在 application.yml 里写 {@code spring.data.redis.cluster.nodes}</b>：
 * Spring Boot 的 {@code RedisAutoConfiguration} 用「属性是否存在」来选拓扑，一旦在默认配置里
 * 留一个空占位符，就既不是单机也不是集群，行为取决于绑定后是 null 还是空集合——不可预测。
 * 这里改成显式开关 {@code turbofeed.redis.mode}：
 * <ul>
 *   <li>{@code single}（默认）：本配置<b>一个 Bean 都不创建</b>，完全交给 Boot 自动装配，
 *       与改造前行为逐字节一致，保证「克隆即跑」；</li>
 *   <li>{@code cluster} / {@code sentinel}：由本类注册 {@link LettuceConnectionFactory}，
 *       因 Boot 的自动装配带 {@code @ConditionalOnMissingBean(RedisConnectionFactory)}，本 Bean 自动顶替。</li>
 * </ul>
 * 地址一律来自环境变量 / 外部配置注入，<b>默认配置里不出现任何内网 IP</b>（与 JWT 密钥、MinIO 凭据同一纪律）。</p>
 *
 * <p><b>为什么用 {@code @AutoConfiguration} 而不是普通 {@code @Configuration}</b>：
 * 本模块是<b>库</b>，位于引用方的组件扫描路径之外，普通 {@code @Configuration} 不会被扫到，
 * 引用方必须逐个 {@code @Import}——那是"每加一个服务就要记得导一次"的隐性契约，迟早漏。
 * 改为 {@code @AutoConfiguration} + {@code AutoConfiguration.imports} 后，
 * <b>只要引入依赖即生效</b>，无需任何显式导入。</p>
 *
 * <p><b>顺序 {@code before = RedisAutoConfiguration}</b>：必须<b>先于</b>官方自动装配注册本工厂，
 * 否则 {@code RedisAutoConfiguration} 先按单机建好工厂，本类再注册会变成两个
 * {@code RedisConnectionFactory} → 注入点歧义。先注册则由它的
 * {@code @ConditionalOnMissingBean} 自动退让。</p>
 *
 * <p><b>连接池复用 {@code spring.data.redis.lettuce.pool.*}</b>：不另起一套参数，
 * 避免「单机一套、集群另一套」的漂移——切换拓扑时池的行为保持不变。</p>
 *
 * <p><b>集群化的真实前提</b>（运维侧，不是代码能解决的）：
 * Redis Cluster 至少 3 主 3 从才能容忍单节点故障；Sentinel 至少 3 个哨兵才能避免脑裂误判。</p>
 */
@AutoConfiguration(before = RedisAutoConfiguration.class)
public class RedisTopologyConfig {

    /**
     * 集群模式连接工厂。
     *
     * <p><b>关闭期 "unreleased connections" WARN 修复（#0043）</b>：池化 + {@code shareNativeConnection=false}
     * 下，容器关闭时若 {@code shutdownTimeout} 过短，底层 netty 连接池在仍有在途连接时即被关闭，
     * 触发 Commons-Pool 的 "unreleased connections" 警告。显式声明 {@code destroyMethod="destroy"}
     * 保证工厂随容器优雅销毁、底层池被显式 close；同时将未配置的 {@code shutdownTimeout} 放宽到 2s，
     * 给池足够时间排空在途连接。运行期无影响。单机模式不走本工厂（交 Boot 自动装配），不出现该 WARN。</p>
     *
     * @param nodes        逗号分隔的 {@code host:port} 列表（只需给到部分节点，客户端会自动发现其余）
     * @param maxRedirects MOVED/ASK 重定向最大跳数
     */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(name = "turbofeed.redis.mode", havingValue = "cluster")
    public RedisConnectionFactory redisClusterConnectionFactory(
            RedisProperties properties,
            @Value("${turbofeed.redis.cluster-nodes:}") String nodes,
            @Value("${turbofeed.redis.max-redirects:3}") int maxRedirects) {
        List<String> nodeList = parseNodes(nodes, "cluster-nodes");
        RedisClusterConfiguration config = new RedisClusterConfiguration(nodeList);
        config.setMaxRedirects(maxRedirects);
        applyPassword(config, properties);
        return lettuceFactory(config, properties);
    }

    /** 哨兵模式连接工厂（关闭期 WARN 修复同集群工厂，见 {@link #redisClusterConnectionFactory}）。 */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(name = "turbofeed.redis.mode", havingValue = "sentinel")
    public RedisConnectionFactory redisSentinelConnectionFactory(
            RedisProperties properties,
            @Value("${turbofeed.redis.sentinel-master:}") String master,
            @Value("${turbofeed.redis.sentinel-nodes:}") String nodes) {
        if (!StringUtils.hasText(master)) {
            // 与 JWT 密钥同手法：关键配置缺失即启动失败，绝不静默回落到单机（那会「假装高可用」）。
            throw new IllegalStateException("turbofeed.redis.mode=sentinel 时必须配置 turbofeed.redis.sentinel-master");
        }
        Set<String> nodeSet = new LinkedHashSet<>(parseNodes(nodes, "sentinel-nodes"));
        RedisSentinelConfiguration config = new RedisSentinelConfiguration(master, nodeSet);
        applyPassword(config, properties);
        if (StringUtils.hasText(properties.getSentinel().getPassword())) {
            config.setSentinelPassword(properties.getSentinel().getPassword());
        }
        return lettuceFactory(config, properties);
    }

    private static void applyPassword(RedisClusterConfiguration config, RedisProperties properties) {
        if (StringUtils.hasText(properties.getPassword())) {
            config.setPassword(properties.getPassword());
        }
    }

    private static void applyPassword(RedisSentinelConfiguration config, RedisProperties properties) {
        if (StringUtils.hasText(properties.getPassword())) {
            config.setPassword(properties.getPassword());
        }
    }

    private static LettuceConnectionFactory lettuceFactory(Object topologyConfig, RedisProperties properties) {
        LettuceClientConfiguration client = lettuceClientConfiguration(properties);
        LettuceConnectionFactory factory = topologyConfig instanceof RedisClusterConfiguration cluster
                ? new LettuceConnectionFactory(cluster, client)
                : new LettuceConnectionFactory((RedisSentinelConfiguration) topologyConfig, client);
        // 启用连接池时必须关闭共享连接：池化后每次 getConnection 都从池借，共享连接与之语义冲突。
        factory.setShareNativeConnection(false);
        return factory;
    }

    private static LettuceClientConfiguration lettuceClientConfiguration(RedisProperties properties) {
        Duration timeout = properties.getTimeout() == null ? Duration.ofMillis(500) : properties.getTimeout();
        // 关闭期排空超时：默认仅 100ms（Spring Boot 默认），池化 + shareNativeConnection=false 下
        // 在途连接往往来不及归还就被关，触发 "unreleased connections" WARN。未显式配置时放宽到 2s，
        // 给底层 Commons-Pool 足够时间排空（运行期无任何影响，只在进程关闭时起作用）。
        Duration shutdownTimeout = properties.getLettuce().getShutdownTimeout();
        if (shutdownTimeout == null || shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            shutdownTimeout = Duration.ofSeconds(2);
        }
        // 注意：poolConfig(...) 只存在于 LettucePoolingClientConfigurationBuilder 上，
        // 用父类型 LettuceClientConfigurationBuilder 接住会编译不过（方法不在父接口）。
        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder()
                        .commandTimeout(timeout)
                        .shutdownTimeout(shutdownTimeout);
        RedisProperties.Pool pool = properties.getLettuce().getPool();
        GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(pool.getMaxActive());
        poolConfig.setMaxIdle(pool.getMaxIdle());
        poolConfig.setMinIdle(pool.getMinIdle());
        if (pool.getMaxWait() != null) {
            poolConfig.setMaxWait(pool.getMaxWait());
        }
        return builder.poolConfig(poolConfig).build();
    }

    private static List<String> parseNodes(String raw, String key) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("turbofeed.redis." + key + " 不能为空（当前 Redis 拓扑不是单机，却没有给节点地址）");
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
