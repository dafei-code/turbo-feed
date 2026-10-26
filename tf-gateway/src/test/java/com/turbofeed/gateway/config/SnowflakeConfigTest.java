package com.turbofeed.gateway.config;

import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 雪花 ID 生成器的两条取值路径装配（M2 / 4.3）。
 *
 * <p><b>为什么专门测装配而不是只测逻辑</b>：本改造用 {@code @ConditionalOnProperty} 做两条路径的
 * 互斥切换，而这个注解<b>按字面量查 Environment，不做 relaxed binding</b>——属性名与注解里写的
 * 字符串<b>必须逐字一致</b>，写错了不会报错，只会「开关不动」。这正是 M1/3.2 在 tf-counter 上
 * 踩过的坑（三个开关键名不一致，导致「集群模式 + ShedLock 选主」从未真正生效）。
 * 只测正常逻辑是抓不到的，必须把「开关拨到某个位置时，谁被装配、谁没被装配」断言出来。</p>
 */
class SnowflakeConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SnowflakeConfig.class);

    @Test
    void 默认走显式配置路径且只装配一个生成器() {
        runner.withPropertyValues(
                        "turbofeed.snowflake.worker-id=3",
                        "turbofeed.snowflake.datacenter-id=1")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SnowflakeIdGenerator.class);
                    assertThat(ctx.getBean(SnowflakeIdGenerator.class)).isNotNull();
                    // 关键：不提供 StringRedisTemplate 也能启动 —— 证明租约路径未激活，
                    // 「克隆即跑」没有被一个新功能悄悄破坏
                    assertThat(ctx).doesNotHaveBean(WorkerIdLeaseManager.class);
                });
    }

    @Test
    void lease关闭时同样走显式配置路径() {
        runner.withPropertyValues(
                        "turbofeed.snowflake.lease-enabled=false",
                        "turbofeed.snowflake.worker-id=5",
                        "turbofeed.snowflake.datacenter-id=2")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SnowflakeIdGenerator.class);
                    assertThat(ctx).doesNotHaveBean(WorkerIdLeaseManager.class);
                });
    }

    @Test
    void lease开启时改走租约路径且仍然只装配一个生成器() {
        runner.withBean(StringRedisTemplate.class, this::mockRedis)
                .withPropertyValues(
                        "turbofeed.snowflake.lease-enabled=true",
                        "turbofeed.snowflake.datacenter-id=1")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SnowflakeIdGenerator.class);
                    assertThat(ctx).hasSingleBean(WorkerIdLeaseManager.class);
                    WorkerIdLeaseManager lease = ctx.getBean(WorkerIdLeaseManager.class);
                    assertThat(lease.workerId()).isBetween(0, WorkerIdLeaseManager.MAX_WORKER_ID);
                    assertThat(lease.isHeld()).isTrue();
                });
    }

    @Test
    void 显式配置缺失时启动失败且报错点名环境变量() {
        // 只断言 hasFailed() 太弱——任何原因的失败都会通过。必须锁定失败原因里点名了
        // 该配哪个环境变量，否则「启动失败但运维不知道配什么」依然是个可用性问题。
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasMessageContaining("缺少雪花 ID 实例标识")
                    .hasMessageContaining("TURBOFEED_SNOWFLAKE_WORKER_ID");
        });
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        return redis;
    }
}
