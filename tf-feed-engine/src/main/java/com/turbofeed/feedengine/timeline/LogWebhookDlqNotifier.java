package com.turbofeed.feedengine.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 死信告警出口默认实现（P0-2 DLQ 告警部署接线）：日志 ERROR + 可选 webhook POST。
 *
 * <p><b>fail-open</b>：webhook 未配置（空串）时只打日志，不发网络请求；webhook 配置但发送失败
 * （网络/非 2xx/超时）仅告警、绝不抛——告警本身绝不能拖垮引擎主流程，更不能因告警失败把
 * 死信积压这件事掩盖掉（最坏情况是"告警没发出去但日志还在"，运维仍可从日志侧发现）。</p>
 *
 * <p><b>为什么用 JDK {@code HttpClient} 而非再引一个 rest 客户端</b>：告警是个旁路信号，
 * 零业务依赖；JDK 自带、无新依赖、fail-open 足够。JSON 用应用级 {@link ObjectMapper} 现成拼。</p>
 */
@Slf4j
@Component
public class LogWebhookDlqNotifier implements DlqNotifier {

    private final ObjectMapper objectMapper;
    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public LogWebhookDlqNotifier(ObjectMapper objectMapper,
                                 @Value("${turbofeed.feed.timeline.dlq-alert.webhook-url:}") String webhookUrl) {
        this.objectMapper = objectMapper;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl;
    }

    @Override
    public void notify(DlqAlert alert) {
        // 日志是兜底通道：无论 webhook 是否配置/成功，ERROR 级别日志一定留痕
        log.error("【DLQ 告警】时间线死信积压达到阈值: count={}, engineId={}, samples={}",
                alert.count(), alert.engineId(), alert.samples());
        if (webhookUrl.isBlank()) {
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "alert", "FEED_TIMELINE_DLQ",
                    "engineId", alert.engineId(),
                    "count", alert.count(),
                    "timestamp", alert.timestamp(),
                    "samples", alert.samples()));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("DLQ 告警 webhook 返回非 2xx（fail-open，不影响主流程）: status={}", resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("DLQ 告警 webhook 发送失败（fail-open，日志仍留痕）: {}", e.getMessage());
        }
    }
}
