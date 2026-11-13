package com.turbofeed.gateway.service.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 语义层 HTTP 实现：调用独立 sidecar（embedding + 近邻检索）。
 *
 * <p>请求：{@code POST {endpoint}/score}，body {@code {"text": "归一化文本"}}。
 * 响应：{@code {"score":0.0,"nearestWord":"...","category":"...","modelVersion":"..."}}。</p>
 *
 * <p><b>fail-open</b>：任何异常（连接失败/超时/非 2xx/反序列化失败）都吞掉并返回
 * {@link SemanticScore#unavailable()}，绝不向调用方抛异常——语义层不可用时规则层照常工作。</p>
 */
public class HttpSemanticScorer implements ContentScorer {

    private static final Logger log = LoggerFactory.getLogger(HttpSemanticScorer.class);

    private final RestClient client;
    private final ContentSecurityProperties.Semantic props;

    public HttpSemanticScorer(ContentSecurityProperties.Semantic props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());
        rf.setReadTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());
        this.client = RestClient.builder().baseUrl(props.getEndpoint()).requestFactory(rf).build();
    }

    @Override
    public SemanticScore score(String normalizedText, long userId) {
        if (normalizedText == null || normalizedText.isEmpty()) {
            return SemanticScore.unavailable();
        }
        long t0 = System.nanoTime();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = client.post()
                    .uri("/score")
                    .body(Map.of("text", normalizedText, "userId", userId))
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                return SemanticScore.unavailable();
            }
            double score = ((Number) resp.getOrDefault("score", 0.0)).doubleValue();
            String nearest = (String) resp.get("nearestWord");
            String category = (String) resp.get("category");
            String version = (String) resp.get("modelVersion");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new SemanticScore(score, nearest, category, version, ms, true);
        } catch (Exception e) {
            log.debug("语义层 sidecar 不可用(fail-open, 放行): {}", e.getMessage());
            return SemanticScore.unavailable();
        }
    }
}
