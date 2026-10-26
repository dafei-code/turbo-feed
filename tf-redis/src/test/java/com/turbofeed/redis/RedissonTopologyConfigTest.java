package com.turbofeed.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedissonTopologyConfig} 装配契约测试（<b>不连真实 Redis</b>）。
 *
 * <p>本类的价值在于把三条<b>一旦破防就是静默失效</b>的契约钉死：
 * <ol>
 *   <li><b>opt-in</b>：未显式开启时零 Bean、零连接。{@code Redisson.create()} 会立即建连，
 *       若哪天把开关拿掉，所有服务在「没配 Redis 的环境」启动即失败；</li>
 *   <li><b>缺拓扑即启动失败</b>：cluster / sentinel 没给节点地址时抛 {@code IllegalStateException}，
 *       绝不静默回落单机——回落单机等于「假装高可用」；</li>
 *   <li><b>连接参数单一来源</b>：客户端读的是 {@code spring.data.redis.*}（{@link RedisProperties}），
 *       与各服务的 Lettuce 侧同一份，不再有第二套地址。</li>
 * </ol>
 *
 * <p>为什么只测「失败路径」而不测「成功建连」：成功路径必然要连 Redis，属于集成测试范畴；
 * 而失败路径恰好是最容易被后人「顺手放宽」的部分，故在此固化。</p>
 */
class RedissonTopologyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedissonTopologyConfig.class))
            .withBean(RedisProperties.class);

    /** 未显式开启 → 一个 RedissonClient 都不建（也就不会去连 127.0.0.1:6379）。 */
    @Test
    void 未开启时不装配任何客户端() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(RedissonClient.class)).isEmpty();
            assertThat(ctx.containsBean("redissonSingleClient")).isFalse();
            assertThat(ctx.containsBean("redissonClusterClient")).isFalse();
            assertThat(ctx.containsBean("redissonSentinelClient")).isFalse();
        });
    }

    /** 开关为 false（而非缺省）时同样零 Bean——避免「配了 false 却被当成 true」。 */
    @Test
    void 显式关闭时不装配任何客户端() {
        runner.withPropertyValues("turbofeed.redis.redisson-enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(RedissonClient.class)).isEmpty();
                });
    }

    /** 集群模式没给节点地址 → 启动失败，且根因是显式异常（不是静默回落单机）。 */
    @Test
    void 集群模式缺节点地址时启动失败() {
        runner.withPropertyValues(
                        "turbofeed.redis.redisson-enabled=true",
                        "turbofeed.redis.mode=cluster")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable root = rootCause(ctx.getStartupFailure());
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root).hasMessageContaining("cluster-nodes");
                });
    }

    /** 哨兵模式没给 master → 启动失败（与 RedisTopologyConfig 同口径，绝不假装高可用）。 */
    @Test
    void 哨兵模式缺主节点名时启动失败() {
        runner.withPropertyValues(
                        "turbofeed.redis.redisson-enabled=true",
                        "turbofeed.redis.mode=sentinel",
                        "turbofeed.redis.sentinel-nodes=127.0.0.1:26379")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable root = rootCause(ctx.getStartupFailure());
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root).hasMessageContaining("sentinel-master");
                });
    }

    /** 哨兵模式给了 master 但没给哨兵地址 → 同样失败（防「只有 master 也能起来」的半截配置）。 */
    @Test
    void 哨兵模式缺哨兵地址时启动失败() {
        runner.withPropertyValues(
                        "turbofeed.redis.redisson-enabled=true",
                        "turbofeed.redis.mode=sentinel",
                        "turbofeed.redis.sentinel-master=mymaster")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable root = rootCause(ctx.getStartupFailure());
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    // 报错必须点名具体是哪个键没配，否则运维只能对着「节点地址不能为空」逐个猜
                    assertThat(root).hasMessageContaining("sentinel-nodes");
                });
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}
