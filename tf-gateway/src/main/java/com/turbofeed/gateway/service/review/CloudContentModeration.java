package com.turbofeed.gateway.service.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbofeed.gateway.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 云内容安全 API 机审实现（feature-match M2，通用适配层，moderation-mode=cloud）。
 *
 * <p>视觉语义初审：下载媒体原图 -> Base64 -> 调云内容安全 API -> {@link CloudResponseParser}
 * 映射为 {@link ModerationVerdict}（真实置信度/标签/是否强制人审）。不绑定具体厂商：
 * 端点/密钥经配置（环境变量注入本地配置）提供，响应解析用 {@link GenericCloudResponseParser}。</p>
 *
 * <p><b>fail-closed</b>：未配置且非 simulate、网络/解析异常，一律返回
 * {@code ModerationVerdict(APPROVED, 0.0, [], true)} —— 强制送人审，不静默放公域。</p>
 *
 * <p><b>simulate</b>：{@code cloud.simulate=true} 时不发真实请求，用内置样例响应跑通映射，
 * 供无 key 环境验证梯度分流（confidence<阈值 → 强制人审）。</p>
 */
@Component
public class CloudContentModeration implements ContentModeration {

    private static final Logger log = LoggerFactory.getLogger(CloudContentModeration.class);

    private final MediaProperties.Review.Cloud cloud;
    private final CloudResponseParser parser;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public CloudContentModeration(MediaProperties mediaProperties, CloudResponseParser parser) {
        this.cloud = mediaProperties.getReview().getCloud();
        this.parser = parser != null ? parser : new GenericCloudResponseParser();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cloud.getTimeoutMs()))
                .build();
    }

    @Override
    public ModerationMode mode() {
        return ModerationMode.CLOUD;
    }

    @Override
    public MediaStatus moderate(String mediaId, long userId, String url) {
        // 老二值端口：委托 verdict 的 decision，保证旧调用方也走云逻辑
        return verdict(mediaId, userId, url).decision();
    }

    @Override
    public ModerationVerdict verdict(String mediaId, long userId, String url) {
        try {
            JsonNode root;
            if (cloud.isSimulate()) {
                root = simulateResponse();
            } else {
                if (cloud.getEndpoint() == null || cloud.getEndpoint().isBlank()
                        || cloud.getAccessKey() == null || cloud.getAccessKey().isBlank()) {
                    log.warn("云机审未配置端点/密钥（fail-closed 转人审）: mediaId={}, userId={}", mediaId, userId);
                    return new ModerationVerdict(MediaStatus.APPROVED, 0.0, List.of(), true);
                }
                String body = callCloud(url);
                root = objectMapper.readTree(body);
            }
            return parser.parse(root, mediaId, userId);
        } catch (Exception e) {
            log.warn("云机审异常（fail-closed 转人审）: mediaId={}, userId={}, {}", mediaId, userId, e.getMessage());
            return new ModerationVerdict(MediaStatus.APPROVED, 0.0, List.of(), true);
        }
    }

    /** 调云内容安全 API：下载原图 -> Base64 -> POST endpoint（鉴权头占位，厂商特化 TODO）。 */
    private String callCloud(String url) throws Exception {
        byte[] imageBytes = download(url);
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String payload = buildPayload(b64);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cloud.getEndpoint()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(cloud.getTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("云内容安全 API 返回 status=" + resp.statusCode());
        }
        return resp.body();
    }

    private byte[] download(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(cloud.getTimeoutMs()))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2 || resp.body() == null || resp.body().length == 0) {
            throw new IllegalStateException("下载媒体失败 status=" + resp.statusCode());
        }
        return resp.body();
    }

    /** 请求体（通用占位：厂商鉴权/字段差异留 TODO，如阿里云绿网/腾讯天御签名与字段差异）。 */
    private String buildPayload(String b64) {
        return "{\"image\":\"" + b64 + "\"}";
    }

    /** 模拟响应：中置信度「通过」，用于验证映射 + 梯度分流（confidence 0.63 < 0.9 → 强制人审）。 */
    private JsonNode simulateResponse() throws Exception {
        String sample = "{\"suggestion\":\"pass\",\"score\":0.63,\"labels\":[\"simulate\",\"ad\"],\"reason\":\"模拟通过-中置信度\"}";
        return objectMapper.readTree(sample);
    }
}
