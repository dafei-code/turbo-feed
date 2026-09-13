package com.turbofeed.gateway.config;

import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * <p>默认值 1/1 仅供<b>单实例</b>本地开发；命中默认值时会打印 WARN，提示扩容前必须区分。</p>
 */
@Slf4j
@Configuration
public class SnowflakeConfig {

    /** 默认实例标识（单实例本地开发）。多实例必须用环境变量覆盖。 */
    private static final long DEFAULT_INSTANCE_ID = 1L;

    /**
     * 雪花 ID 生成器（单例）。workerId / datacenterId 由配置注入，
     * 不允许多实例共用同一取值。
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${turbofeed.snowflake.worker-id:1}") long workerId,
            @Value("${turbofeed.snowflake.datacenter-id:1}") long datacenterId) {
        if (workerId == DEFAULT_INSTANCE_ID && datacenterId == DEFAULT_INSTANCE_ID) {
            log.warn("雪花 ID 使用默认实例标识 (workerId={}, datacenterId={})——仅供单实例本地开发；"
                            + "多实例部署前必须逐实例注入 turbofeed.snowflake.worker-id / datacenter-id"
                            + "（env: TURBOFEED_SNOWFLAKE_WORKER_ID / TURBOFEED_SNOWFLAKE_DATACENTER_ID），"
                            + "否则同一毫秒会生成相同 ID 导致主键冲突",
                    DEFAULT_INSTANCE_ID, DEFAULT_INSTANCE_ID);
        }
        log.info("雪花 ID 生成器初始化: workerId={}, datacenterId={}", workerId, datacenterId);
        return new SnowflakeIdGenerator(workerId, datacenterId);
    }
}
