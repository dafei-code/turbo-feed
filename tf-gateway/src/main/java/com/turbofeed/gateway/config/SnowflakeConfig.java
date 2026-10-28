package com.turbofeed.gateway.config;

import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 雪花 ID 生成器装配（{@code turbofeed.snowflake.*}）。
 *
 * <p><b>为什么必须外部化 workerId / datacenterId</b>：雪花 ID 由
 * {@code (时间戳, datacenterId, workerId, sequence)} 拼接而成。两个实例若共用同一组
 * workerId/datacenterId，<b>在同一毫秒内必然生成完全相同的 ID</b>——user 表主键插入会直接抛
 * {@code DuplicateKeyException}（注册失败），comment 同理。所以多实例部署必须逐实例注入不同取值：
 * <ul>
 *   <li>K8s：用 StatefulSet 的序号派生（或读 {@code POD_NAME} 后取尾号）；</li>
 *   <li>物理机 / 虚拟机：按实例清单分配，写进启动参数或环境变量。</li>
 * </ul>
 * 取值范围各 0~31（各 5 bit）。</p>
 *
 * <p><b>无默认值（刻意）</b>：早期实现把 workerId/datacenterId 兜底成 1/1 并只打一条 WARN，
 * 于是「多实例部署漏配环境变量」会静默回退为全部实例共用 (1,1)——正是 P0 撞主键的复发路径，
 * 属于「软防护」而非修复。现改为<b>缺失即启动失败</b>：拿不到显式取值就拒绝启动，把配置错误
 * 暴露在部署阶段，而不是等运行期主键冲突。代价是本地单实例也必须显式指定一次。</p>
 *
 * <p><b>两条互斥的取值路径（M2 / 4.3）</b>：
 * <ul>
 *   <li><b>显式配置模式（默认，{@code lease-enabled=false}）</b>：worker-id 与 datacenter-id
 *       都必须由部署侧注入，缺失即启动失败。适合单机 / 固定实例清单 / 无 Redis 的场景
 *       （保住「克隆即跑」）；</li>
 *   <li><b>自租约模式（{@code lease-enabled=true}）</b>：worker-id 由 {@link WorkerIdLeaseManager}
 *       用 Redis {@code SET NX EX} 抢占并后台续约，datacenter-id 仍显式配置（机房维度不该自动
 *       分配）。扩容加机器不再需要人肉分配号。</li>
 * </ul>
 * 两个 {@code @Bean} 用 {@code @ConditionalOnProperty} 互斥装配，任一时刻只存在一个生成器，
 * <b>不存在「两边都注册然后看谁先」的歧义</b>。共同底线是：两条路都不允许静默重复使用同一个号。</p>
 */
@Slf4j
@Configuration
public class SnowflakeConfig {

    /** 实例标识取值上限（5 bit）。 */
    private static final long MAX_INSTANCE_ID = 31L;

    /**
     * 路径一（默认）：显式配置模式。
     *
     * <p>worker-id 与 datacenter-id 都由配置注入，<b>无兜底默认值</b>：
     * 缺失、非数字或越界一律启动失败。</p>
     */
    @Bean
    @ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.SNOWFLAKE_LEASE_ENABLED, havingValue = "false", matchIfMissing = true)
    public SnowflakeIdGenerator configuredSnowflakeIdGenerator(
            @Value("${turbofeed.snowflake.worker-id:}") String workerIdRaw,
            @Value("${turbofeed.snowflake.datacenter-id:}") String datacenterIdRaw) {
        long workerId = requireInstanceId(workerIdRaw, "turbofeed.snowflake.worker-id",
                "TURBOFEED_SNOWFLAKE_WORKER_ID");
        long datacenterId = requireInstanceId(datacenterIdRaw, "turbofeed.snowflake.datacenter-id",
                "TURBOFEED_SNOWFLAKE_DATACENTER_ID");
        log.info("雪花 ID 生成器初始化（显式配置模式）: workerId={}, datacenterId={}", workerId, datacenterId);
        return new SnowflakeIdGenerator(workerId, datacenterId);
    }

    /**
     * 路径二：自租约模式。worker-id 来自 {@link WorkerIdLeaseManager} 的抢占结果，
     * 并把租约本身作为健康闸门交给生成器——租约续约失败时生成器拒绝出 ID（fail-closed）。
     *
     * <p><b>为什么这里只做装配、抢占发生在 {@code workerIdLeaseManager} 的 Bean 方法里</b>：
     * 本方法依赖租约结果构造生成器，抢占必须在此之前完成。把它写在 Bean 方法里（而不是
     * {@code @PostConstruct}）让「抢占失败 = 启动失败」的时序在调用栈里一眼可见。</p>
     */
    @Bean
    @ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.SNOWFLAKE_LEASE_ENABLED, havingValue = "true")
    public SnowflakeIdGenerator leasedSnowflakeIdGenerator(WorkerIdLeaseManager lease) {
        log.info("雪花 ID 生成器初始化（自租约模式）: workerId={}, datacenterId={}, instanceId={}",
                lease.workerId(), lease.datacenterId(), lease.instanceId());
        return new SnowflakeIdGenerator(lease.workerId(), lease.datacenterId(), lease);
    }

    /**
     * 自租约管理器（仅 {@code lease-enabled=true} 时装配）。
     *
     * <p>抢占<b>在本方法内显式触发</b>：{@code acquire()} 失败（Redis 不可用 / 0~31 全被占满）
     * 直接抛异常，进程起不来——不做任何静默退化。</p>
     */
    @Bean(destroyMethod = "release")
    @ConditionalOnProperty(name = com.turbofeed.shared.feature.FeatureFlagKeys.SNOWFLAKE_LEASE_ENABLED, havingValue = "true")
    public WorkerIdLeaseManager workerIdLeaseManager(StringRedisTemplate redisTemplate,
                                                     @Value("${turbofeed.snowflake.datacenter-id}") long datacenterId,
                                                     @Value("${turbofeed.snowflake.lease-ttl-seconds:60}") long ttlSeconds,
                                                     @Value("${turbofeed.snowflake.lease-renew-interval-seconds:20}") long renewIntervalSeconds) {
        if (datacenterId < 0 || datacenterId > MAX_INSTANCE_ID) {
            throw new IllegalStateException("turbofeed.snowflake.datacenter-id 取值越界（应为 0~"
                    + MAX_INSTANCE_ID + "），当前值: " + datacenterId);
        }
        WorkerIdLeaseManager manager =
                new WorkerIdLeaseManager(redisTemplate, datacenterId, ttlSeconds, renewIntervalSeconds);
        manager.acquire();
        return manager;
    }

    /**
     * 解析实例标识并校验范围，绝不做静默兜底。
     *
     * <p>失败信息直接给出需要设置的环境变量名，避免「启动失败但不知道配什么」。</p>
     */
    private long requireInstanceId(String raw, String propertyKey, String envKey) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "缺少雪花 ID 实例标识 " + propertyKey + "（无默认值，必须显式配置）。"
                            + "请设置环境变量 " + envKey + "（取值 0~" + MAX_INSTANCE_ID + "）；"
                            + "单实例本地开发同样需要指定，多实例部署必须逐实例取不同值，"
                            + "否则同一毫秒会生成相同 ID 导致主键冲突。");
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(propertyKey + " 必须是 0~" + MAX_INSTANCE_ID
                    + " 的整数，当前值: " + raw, e);
        }
        if (value < 0 || value > MAX_INSTANCE_ID) {
            throw new IllegalStateException(propertyKey + " 取值越界（应为 0~" + MAX_INSTANCE_ID
                    + "），当前值: " + value);
        }
        return value;
    }
}
