package com.turbofeed.gateway.config;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Sentinel 流控规则 Nacos 数据源（P0-5，ADR-002，opt-in）。
 *
 * <p><b>激活条件</b>：仅当 {@code turbofeed.sentinel.nacos.enabled=true} 时本配置才生效。
 * 关闭（默认）时本 Bean 不创建，{@link SentinelRateLimitConfig} 继续用本地代码注册规则，克隆即跑。</p>
 *
 * <p><b>为什么手写而非用 spring-cloud-alibaba-sentinel 的自动 datasource 机制</b>：
 * SCA 的 {@code spring.cloud.sentinel.datasource.*} 自动装配会把「存在该配置段」当作激活信号，
 * 难以做到与现有本地规则「互斥且默认关闭」。这里手动建 {@link NacosDataSource} 并显式
 * {@link FlowRuleManager#register2Property} 绑定，开关完全由 {@code turbofeed.sentinel.nacos.enabled}
 * 控制，与本地规则 {@link ConditionalOnProperty} 互斥，避免「两套规则互相覆盖」的隐性坑。</p>
 *
 * <p><b>解析用 Jackson 而非 fastjson</b>：Sentinel 默认序列化走 fastjson，但本项目已在多处规避 fastjson
 * （见 MediaProperties 注释）。这里用应用级 {@link ObjectMapper} 把 Nacos 下发的 JSON 反序列化成
 * {@code List<FlowRule>}，规则体只需含 Sentinel FlowRule 的标准字段（resource / grade / count / limitApp 等），
 * 与本地 {@link SentinelRateLimitConfig#initRules} 注册的同一种规则。改规则只需在 Nacos 控制台改 dataId，
 * 不用重启网关。</p>
 */
@Configuration
@ConditionalOnProperty(name = "turbofeed.sentinel.nacos.enabled", havingValue = "true")
public class SentinelNacosDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelNacosDataSourceConfig.class);

    @Bean
    public NacosDataSource<List<FlowRule>> flowRuleDataSource(
            @Value("${turbofeed.sentinel.nacos.server-addr:}") String serverAddr,
            @Value("${turbofeed.sentinel.nacos.group:DEFAULT_GROUP}") String group,
            @Value("${turbofeed.sentinel.nacos.data-id:sentinel-flow-rules}") String dataId,
            ObjectMapper objectMapper) {
        Converter<String, List<FlowRule>> parser = s -> {
            try {
                return objectMapper.readValue(s,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FlowRule.class));
            } catch (Exception e) {
                throw new IllegalArgumentException("Sentinel 流控规则(Nacos)解析失败: " + e.getMessage(), e);
            }
        };
        NacosDataSource<List<FlowRule>> ds = new NacosDataSource<>(serverAddr, group, dataId, parser);
        // 必须显式把数据源 Property 绑定到 FlowRuleManager，否则下发的规则不会生效
        // （NacosDataSource 自身不自动注册到具体规则管理器）。
        FlowRuleManager.register2Property(ds.getProperty());
        log.info("Sentinel 流控规则数据源已切换为 Nacos: serverAddr={}, group={}, dataId={}"
                + " —— 本地代码注册规则(SentinelRateLimitConfig)已让位", serverAddr, group, dataId);
        return ds;
    }
}
