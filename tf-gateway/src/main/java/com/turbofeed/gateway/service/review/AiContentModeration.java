package com.turbofeed.gateway.service.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * AI 机审实现（Ollama 本地视觉模型）。
 *
 * <p><b>定位</b>：{@link ContentModeration} 端口的一个可插拔实现，对应 {@link ModerationMode#AI}。
 * 与占位桩 {@link AutoPassModeration} 都作为 Spring Bean 注册，由
 * {@link ContentModerationRouter} 按配置 {@code moderation-mode} 选其一激活，二者不再互斥。</p>
 *
 * <p><b>调用链路</b>：下载媒体原图 -> Base64 -> 调 Ollama {@code /api/chat}（视觉多模态） ->
 * 解析模型返回的 {@code {"safe":bool,"reason":"..."}} -> 映射为 {@link MediaStatus}。</p>
 *
 * <p><b>失败安全（fail-open）</b>：Ollama 未启动 / 模型未拉取 / 网络异常 / 解析失败，
 * 一律降级返回 {@link MediaStatus#APPROVED} ——注意这里「APPROVED」仅表示「机审不拦截」，
 * 在 {@code MediaReviewService#handleUploaded} 中会被导向 PENDING 等人审，<b>不会</b>直接对公域可见。
 * 即：AI 不可用 ≠ 内容裸奔，内容仍卡在人工审核闸。</p>
 *
 * <p><b>策略（用户选择：驳回即拦 · 通过仍人审）</b>：
 * 机审 {@code REJECTED} -> 内容直接翻 REJECTED（不进人工队列，节省人工）；
 * 机审 {@code APPROVED}（含降级）-> 进 PENDING，由管理员终裁。</p>
 *
 * <p><b>依赖</b>：本机需运行 Ollama 并拉取视觉模型（如 {@code ollama pull qwen2.5-vl}），
 * 否则每次上传触发一次失败降级（连接被拒通常瞬时返回，无显著延迟）。Ollama 已卸载时，
 * 将 {@code moderation-mode} 设为 {@code pass} 即可完全跳过本实现，零开销。</p>
 */
@Slf4j
@Component
public class AiContentModeration implements ContentModeration {

    @Override
    public ModerationMode mode() {
        return ModerationMode.AI;
    }

    private final String baseUrl;
    private final String model;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiContentModeration(
            @Value("${turbofeed.media.review.ai-base-url:http://localhost:11434}") String baseUrl,
            @Value("${turbofeed.media.review.ai-model:qwen2.5-vl}") String model) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;

    private final String baseUrl;
    private final String model;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiContentModeration(
            @Value("${turbofeed.media.review.ai-base-url:http://localhost:11434}") String baseUrl,
            @Value("${turbofeed.media.review.ai-model:qwen2.5-vl}") String model) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
    }

    @Override
    public MediaStatus moderate(String mediaId, long userId, String url) {
        try {
            byte[] imageBytes = download(url);
            String answer = chat(imageBytes);
            return decide(answer, mediaId, userId);
        } catch (Exception e) {
            // 任何异常（下载失败 / Ollama 未起 / 超时 / 解析失败）安全降级为「待人审」
            log.warn("AI 机审异常（降级为待人审，不自动放行）: mediaId={}, userId={}, {}", mediaId, userId, e.getMessage());
            return MediaStatus.APPROVED;
        }
    }

    /** 下载媒体原图（供视觉模型识别）；失败抛异常交由上层降级。 */
    private byte[] download(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2 || resp.body() == null || resp.body().length == 0) {
            throw new IllegalStateException("下载媒体失败 status=" + resp.statusCode());
        }
        return resp.body();
    }

    /** 调 Ollama 视觉对话，返回模型文本（含 JSON）。 */
    private String chat(byte[] imageBytes) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String payload = buildPayload(b64);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Ollama 返回 status=" + resp.statusCode() + ", body=" + resp.body());
        }
        return extractContent(resp.body());
    }

    /** 组装 Ollama 请求体：强制 JSON 输出，要求模型判定 safe 布尔。 */
    private String buildPayload(String b64) {
        String prompt = "你是内容安全审核员。请判断这张图片是否违规（涉黄、暴恐、涉政、赌博、"
                + "广告引流、二维码引流等）。只输出 JSON，不要任何多余文字，格式严格为："
                + "{\"safe\": true 或 false, \"reason\": \"一句话原因\"}";
        // 视觉消息：content + images（base64 数组）
        return "{\"model\":\"" + model + "\",\"stream\":false,\"format\":\"json\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\""
                + escapeJson(prompt) + "\",\"images\":[\"" + b64 + "\"]}]}";
    }

    /** 从 Ollama 响应提取 assistant 文本。 */
    private String extractContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode message = root.get("message");
            if (message != null && message.get("content") != null) {
                return message.get("content").asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 响应（如错误串）直接返回原文，decide 会解析失败再降级
        }
        return body;
    }

    /** 解析模型输出，映射为审核状态：safe=false -> REJECTED；其余 -> APPROVED（待人工）。 */
    private MediaStatus decide(String answer, String mediaId, long userId) {
        try {
            JsonNode node = objectMapper.readTree(answer);
            JsonNode safe = node.get("safe");
            if (safe != null && !safe.asBoolean()) {
                String reason = node.has("reason") ? node.get("reason").asText() : "AI 判定违规";
                log.info("AI 机审命中违规（直接拦截）: mediaId={}, userId={}, reason={}", mediaId, userId, reason);
                return MediaStatus.REJECTED;
            }
            return MediaStatus.APPROVED;
        } catch (Exception e) {
            // 模型未返回规范 JSON（如瞎聊），保守放行到人审，不自动拦截
            log.warn("AI 机审结果解析失败（降级为待人审）: mediaId={}, answer={}", mediaId,
                    answer.length() > 200 ? answer.substring(0, 200) : answer);
            return MediaStatus.APPROVED;
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
