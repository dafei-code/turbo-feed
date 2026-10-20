package com.turbofeed.feedengine.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static com.turbofeed.feedengine.interceptor.InternalTokenInterceptor.HEADER;

/**
 * 网关「流量池晋级加严」内部回调客户端（引擎 → 网关，同步 fail-open）。
 *
 * <p>内容晋级到更高流量池时，{@code FeedPoolPromoter} 通过本类回调网关
 * {@code /internal/review/pool-promoted?postId=&from=&to=}，由网关执行三件套加严
 * （机审复扫 + 建 POOL_PROMOTED 复审任务 + 按池级收紧热度阈值）。</p>
 *
 * <p><b>fail-open</b>：网关不可达 / 超时 / 非 2xx 一律仅告警返回 {@code false}，
 * <b>绝不阻断晋级扫描主流程</b>——晋级已在引擎侧完成（{@code FeedTimelineStore#promote} 成功），
 * 加严是旁路治理。与网关 {@code FeedEngineClient} 同源语义。</p>
 *
 * <p><b>超时</b>：建连/读取超时显式配置（默认 500ms / 2s），避免网关故障时扫描线程被慢调用拖垮。
 * 与网关侧 {@code turbofeed.feed.engine.*} 超时一致口径。</p>
 */
@Slf4j
@Component
public class GatewayReviewClient {

    private static final String PATH_POOL_PROMOTED = "/internal/review/pool-promoted";

    private final RestClient restClient;
    private final String baseUrl;
    /** 网关服务名（Nacos 注册名，空 = 直连 base-url）。见 application.yml turbofeed.gateway.service-name。 */
    private final String serviceName;
    /** 服务间内部接口共享令牌（评审 doc P0-2）；为空时不为请求附加 X-Internal-Token（fail-open 本地开发）。 */
    private final String internalToken;

    /**
     * 直连形态（默认）：用 {@code directRestClientBuilder}（{@code ServiceDiscoveryRestClientConfig}
     * 显式声明的直连 builder）+ {@code base-url}。
     * 服务发现形态：当 {@code serviceName} 非空时改用 {@code @LoadBalanced} builder +
     * {@code baseUrl("http://" + serviceName)}，由 Nacos 注册表解析真实网关实例。
     * 注入的两个 builder 由 {@code ServiceDiscoveryRestClientConfig} 提供（共存、按需选用）。
     * <p><b>为什么必须显式限定</b>：Boot 的 plain builder 因本工程自定义了
     * {@code RestClient.Builder} Bean 而 {@code @ConditionalOnMissingBean} 退避，
     * 上下文中 builder 候选只剩显式声明的两个——不加限定符会因双候选歧义启动失败，
     * 误注入 {@code @LoadBalanced} 版则直连模式被 LB 拦截。</p>
     */
    public GatewayReviewClient(@Value("${turbofeed.gateway.base-url:http://localhost:8080}") String baseUrl,
                               @Value("${turbofeed.gateway.service-name:}") String serviceName,
                               @Value("${turbofeed.gateway.connect-timeout-ms:500}") int connectTimeoutMs,
                               @Value("${turbofeed.gateway.read-timeout-ms:2000}") int readTimeoutMs,
                               @Value("${turbofeed.internal.token:}") String internalToken,
                               @Qualifier("directRestClientBuilder") RestClient.Builder restClientBuilder,
                               @LoadBalanced RestClient.Builder lbRestClientBuilder) {
        this.baseUrl = baseUrl;
        this.serviceName = serviceName == null ? "" : serviceName;
        this.internalToken = internalToken == null ? "" : internalToken;
        boolean useDiscovery = !this.serviceName.isBlank();
        RestClient.Builder chosenBuilder = useDiscovery ? lbRestClientBuilder : restClientBuilder;
        String resolvedBaseUrl = useDiscovery ? "http://" + this.serviceName : baseUrl;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restClient = chosenBuilder.clone()
                .baseUrl(resolvedBaseUrl)
                .requestFactory(requestFactory)
                // 服务间内部接口鉴权（评审 doc P0-2）：配置令牌时为回调网关 /internal/review/** 加 X-Internal-Token，
                // 与网关侧 InternalTokenInterceptor 共享同一密钥。未配置令牌则不加（fail-open 本地开发）。
                .defaultHeaders(headers -> {
                    if (!this.internalToken.isBlank()) {
                        headers.set(HEADER, this.internalToken);
                    }
                })
                .build();
        log.info("网关回调客户端初始化: mode={}, baseUrl={}, connectTimeoutMs={}, readTimeoutMs={}, internalToken={}",
                useDiscovery ? "服务发现(Nacos)" : "直连", resolvedBaseUrl,
                connectTimeoutMs, readTimeoutMs, this.internalToken.isBlank() ? "未配置(fail-open)" : "已配置");
    }

    /**
     * 通知网关：内容从 {@code from} 池晋级到 {@code to} 池，触发加严复审。
     *
     * @return true = 请求送达网关；false = 投递失败（fail-open，调用方可忽略）
     */
    public boolean notifyPoolPromoted(String postId, int from, int to) {
        if (postId == null || postId.isBlank()) {
            return false;
        }
        try {
            restClient.post()
                    .uri(uri -> uri.path(PATH_POOL_PROMOTED)
                            .queryParam("postId", postId)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("流量池晋级回调网关失败（fail-open，不影响晋级主流程）: postId={}, {}→{}, {}",
                    postId, from, to, e.getMessage());
            return false;
        }
    }
}
