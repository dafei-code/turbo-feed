package com.turbofeed.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 服务发现接入点（P0-5，ADR-002）：提供<b>带 LoadBalancer 拦截器</b>的 {@link RestClient.Builder}。
 *
 * <p><b>为什么需要它</b>：{@link FeedEngineClient} 现默认直连固定 host:port
 * （{@code turbofeed.feed.engine.base-url}）。引入 Nacos 服务发现后，需要一种「用服务名寻址」的
 * builder——当 base-url 写成 {@code http://服务名} 时，拦截器会把服务名解析成 Nacos 注册表中的
 * 真实实例（健康检查 + 负载均衡 + 故障摘除），而不是去 DNS 查一个字面量 host。</p>
 *
 * <p><b>与 Boot 自带 builder 的关系</b>：Spring Boot 自动装配的 plain {@code RestClient.Builder}
 * （无 {@link LoadBalanced} 限定符）继续存在，用于「直连」场景；本类额外提供一个被
 * {@code @LoadBalanced} 限定的 builder，用于「服务名」场景。两者共存、互不冲突：
 * 调用方按 {@code turbofeed.feed.engine.service-name} 是否为空二选一（见 {@code FeedEngineClient}）。</p>
 *
 * <p><b>克隆即跑语义</b>：本 Bean 轻量、恒创建，但<b>不会</b>主动连 Nacos——是否真能解析服务名，
 * 取决于 {@code spring.cloud.nacos.discovery.enabled}（默认 {@code false}，见 application.yml）。
 * 仅当调用方传入非空服务名且 discovery 已开启时，拦截器才会向注册表发起解析；
 * 服务名为空时 {@code FeedEngineClient} 走 plain builder 直连，本 Bean 不被使用，行为 100% 与改造前一致。</p>
 */
@Configuration
public class ServiceDiscoveryRestClientConfig {

    /**
     * 带负载均衡拦截器的 {@link RestClient.Builder}。
     *
     * <p>{@link LoadBalanced} 是 Spring Cloud 的 {@code @Qualifier("loadBalanced")}——
     * 注入时加该注解即可拿到本实例，否则拿到 Boot 的 plain 实例。调用方用
     * {@code .clone().baseUrl("http://服务名")} 后，请求会被 LB 拦截器接管寻址。</p>
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
