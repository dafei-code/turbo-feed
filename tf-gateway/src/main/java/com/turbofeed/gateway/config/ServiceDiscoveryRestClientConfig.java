package com.turbofeed.gateway.config;

import com.turbofeed.gateway.trace.TracePropagationInterceptor;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * 服务发现接入点（P0-5，ADR-002）：提供<b>直连</b>与<b>带 LoadBalancer 拦截器</b>两种
 * {@link RestClient.Builder}。
 *
 * <p><b>为什么需要直连 builder 也显式声明</b>：Boot 自动装配的 plain
 * {@code RestClient.Builder} 带 {@code @ConditionalOnMissingBean}——本类一旦定义了任意
 * {@code RestClient.Builder} Bean，Boot 的 plain builder 就会<b>退避</b>。若只声明
 * {@code @LoadBalanced} 版本，上下文里将只剩这一个被 LB 包装过的 builder，
 * 所有按类型注入的调用方（{@code FeedEngineClient} 直连分支、
 * {@code HttpCounterEventPublisher}）都会拿到它——固定 host:port 的 baseUrl
 * 会被 LoadBalancer 当成服务名解析，直连模式整体失效
 * （实测报 {@code No instances available for 127.0.0.1}）。故直连 builder 必须由本类
 * 显式补回，两个 Bean 共存、调用方按 {@code @Qualifier} 二选一。</p>
 *
 * <p><b>服务名场景</b>：当 base-url 写成 {@code http://服务名} 时，{@code @LoadBalanced}
 * 拦截器把服务名解析成 Nacos 注册表中的真实实例（健康检查 + 负载均衡 + 故障摘除），
 * 而不是去 DNS 查一个字面量 host。</p>
 *
 * <p><b>消息转换器口径</b>：直连 builder 经 Boot 的 {@link RestClientBuilderConfigurer}
 * 构建，与应用共享同一套转换器与 {@code ObjectMapper}（JSR-310 模块）——
 * {@code FeedEngineClient} 契约里的 {@code createdAt} 是 {@code Instant}，
 * 只有应用级 mapper 才注册了 JSR-310 模块，否则日期字段会以时间戳数组形式写出、
 * 引擎侧解析失败。</p>
 *
 * <p><b>克隆即跑语义</b>：两个 Bean 都轻量、恒创建，但<b>不会</b>主动连 Nacos——
 * 是否真能解析服务名，取决于 {@code spring.cloud.nacos.discovery.enabled}
 * （默认 {@code false}，见 application.yml）。仅当调用方传入非空服务名且 discovery
 * 已开启时，LB 拦截器才会向注册表发起解析；服务名为空时调用方走直连 builder，
 * 行为 100% 与改造前一致。prototype 作用域对齐 Boot 自带 builder 的语义，
 * 调用方 clone 后使用。</p>
 */
@Configuration
public class ServiceDiscoveryRestClientConfig {

    /**
     * <b>直连</b> {@link RestClient.Builder}（固定 host:port 的 baseUrl 直达，无 LB 拦截）。
     *
     * <p>Bean 名即限定符：注入方用 {@code @Qualifier("directRestClientBuilder")} 显式选取，
     * 避免与 {@link #loadBalancedRestClientBuilder()} 双候选歧义。经
     * {@link RestClientBuilderConfigurer} 构建以共享应用级转换器（Instant 序列化必需）。</p>
     */
    @Bean
    @Scope("prototype")
    public RestClient.Builder directRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return withTracePropagation(configurer.configure(RestClient.builder()));
    }

    /**
     * 带负载均衡拦截器的 {@link RestClient.Builder}。
     *
     * <p>{@link LoadBalanced} 是 Spring Cloud 的 {@code @Qualifier("loadBalanced")}——
     * 注入时加该注解即可拿到本实例。调用方用
     * {@code .clone().baseUrl("http://服务名")} 后，请求会被 LB 拦截器接管寻址。</p>
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return withTracePropagation(RestClient.builder());
    }

    /**
     * 给出站请求带上当前 traceId（M4）。
     *
     * <p><b>为什么两个 builder 都要加</b>：只加直连那份的话，一旦开启 Nacos 服务发现走 LB 分支，
     * traceId 会在网关→引擎这一跳<b>静默断开</b>——两边都还能正常跑，只是日志再也串不起来，
     * 而且这种事没人会主动去查。这类「只在某种部署形态下失效」的缺口，必须在同一处一起关掉。</p>
     *
     * <p>拦截器本体见 {@link TracePropagationInterceptor}（抽出成类是为了能单测）。</p>
     */
    private static RestClient.Builder withTracePropagation(RestClient.Builder builder) {
        return builder.requestInterceptor(new TracePropagationInterceptor());
    }
}
