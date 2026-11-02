package com.turbofeed.gateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.config.CounterProperties;
import com.turbofeed.gateway.security.InternalTokenInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * tf-counter 的 HTTP 客户端（网关 → 计数服务，同步，fail-open）。
 *
 * <p>与 {@link FeedEngineClient} 同款防腐层：直连 {@code turbofeed.counter.service.base-url}，
 * 共用 {@code directRestClientBuilder}（保证 JSR-310 等消息转换器一致），可附加内部令牌。
 * 所有方法不抛异常：计数服务不可用返回 {@link Optional#empty()}，由调用方按"无数据"处理。</p>
 */
@Slf4j
@Component
public class CounterClient {

    private static final String PATH_BUCKETS = "/internal/counter/buckets";

    private final CounterProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String internalToken;

    public CounterClient(CounterProperties properties,
                         ObjectMapper objectMapper,
                         @Qualifier("directRestClientBuilder") RestClient.Builder restClientBuilder,
                         @Value("${turbofeed.internal.token:}") String internalToken) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken == null ? "" : internalToken;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    if (!this.internalToken.isBlank()) {
                        headers.set(InternalTokenInterceptor.HEADER, this.internalToken);
                    }
                })
                .build();
        log.info("计数服务客户端初始化: baseUrl={}, internalToken={}",
                properties.getBaseUrl(), this.internalToken.isBlank() ? "未配置(fail-open)" : "已配置");
    }

    /**
     * 读取缓冲待落库负载（管理后台「计数分桶负载」面板用）。fail-open：计数服务不可用 / 失败码 / 异常
     * 均返回 {@link Optional#empty()}。
     */
    public Optional<CounterLoad> buckets() {
        try {
            String body = restClient.get()
                    .uri(PATH_BUCKETS)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                log.warn("计数服务分桶负载返回失败码，按无数据处理: code={}", code);
                return Optional.empty();
            }
            JsonNode d = root.path("data");
            if (d.isMissingNode() || d.isNull()) {
                return Optional.of(new CounterLoad(0, 0, 0, 0, 0, 0));
            }
            return Optional.of(new CounterLoad(
                    d.path("pendingPostCount").asLong(0),
                    d.path("totalIncrements").asLong(0),
                    d.path("like").asLong(0),
                    d.path("comment").asLong(0),
                    d.path("share").asLong(0),
                    d.path("commentLike").asLong(0)));
        } catch (Exception e) {
            log.warn("计数服务分桶负载读取失败（fail-open）: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
