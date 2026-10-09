package com.turbofeed.gateway.service.review;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用云响应解析（feature-match M2）：读主流字段名，映射到 {@link ModerationVerdict}。
 *
 * <p><b>决策</b>：{@code suggestion/decision/result/action} 含 block/reject → REJECTED；
 * 含 review/human/needs_review → APPROVED 且 {@code needHumanScan=true}（送人审）；
 * 含 pass/approve/normal → APPROVED。
 * <b>置信度</b>：{@code score}（0~100 或 0~1，>1 视为百分制归一）/ 缺失按决策给默认。
 * <b>标签</b>：{@code labels} 数组或 {@code riskLabel/riskLabels}，并附 {@code reason}。</p>
 *
 * <p>厂商特化（字段名差异）可另写 {@link CloudResponseParser} 实现覆盖本类（TODO）。</p>
 */
@Component
public class GenericCloudResponseParser implements CloudResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GenericCloudResponseParser.class);

    @Override
    public ModerationVerdict parse(JsonNode root, String mediaId, long userId) throws Exception {
        String decision = firstNonBlank(root, "suggestion", "decision", "result", "action");
        double confidence = parseScore(root);
        List<String> labels = parseLabels(root);

        if (decision != null) {
            String d = decision.toLowerCase();
            if (d.contains("block") || d.contains("reject")) {
                log.info("云机审命中违规（直接拦截）: mediaId={}, userId={}, decision={}, labels={}", mediaId, userId, decision, labels);
                return new ModerationVerdict(MediaStatus.REJECTED, 1.0, labels, false);
            }
            if (d.contains("review") || d.contains("human") || d.contains("needs_review")) {
                double c = confidence > 0 ? confidence : 0.5;
                log.info("云机审疑似（送人审）: mediaId={}, userId={}, confidence={}, labels={}", mediaId, userId, c, labels);
                return new ModerationVerdict(MediaStatus.APPROVED, c, labels, true);
            }
            if (d.contains("pass") || d.contains("approve") || d.contains("normal")) {
                double c = confidence > 0 ? confidence : 1.0;
                return new ModerationVerdict(MediaStatus.APPROVED, c, labels, false);
            }
        }
        // 无明确决策：保守放行到人审（不自动放公域）
        log.warn("云机审响应无明确决策（降级送人审）: mediaId={}, decision={}", mediaId, decision);
        return new ModerationVerdict(MediaStatus.APPROVED, confidence > 0 ? confidence : 0.5, labels, true);
    }

    private static String firstNonBlank(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && !n.asText().isBlank()) {
                return n.asText();
            }
        }
        return null;
    }

    private static double parseScore(JsonNode root) {
        JsonNode n = root.get("score");
        if (n == null || n.isNull()) {
            n = root.get("confidence");
        }
        if (n == null || n.isNull()) {
            return 0.0;
        }
        double v = n.asDouble(0.0);
        if (v > 1.0) {
            v = v / 100.0; // 百分制归一
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static List<String> parseLabels(JsonNode root) {
        List<String> labels = new ArrayList<>();
        JsonNode arr = root.get("labels");
        if (arr != null && arr.isArray()) {
            for (JsonNode e : arr) {
                labels.add(e.asText());
            }
        } else {
            JsonNode rl = root.get("riskLabel");
            if (rl != null && !rl.asText().isBlank()) {
                labels.add(rl.asText());
            } else {
                JsonNode rls = root.get("riskLabels");
                if (rls != null && rls.isArray()) {
                    for (JsonNode e : rls) {
                        labels.add(e.asText());
                    }
                }
            }
        }
        JsonNode reason = root.get("reason");
        if (reason != null && !reason.asText().isBlank()) {
            labels.add("reason:" + reason.asText());
        }
        return labels;
    }
}
