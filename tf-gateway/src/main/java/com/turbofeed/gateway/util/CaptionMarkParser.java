package com.turbofeed.gateway.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体描述/标题的标记解析器（抖音式：{@code @用户} / {@code #话题} / {@code [image:idx:filename]}）。
 *
 * <p>输入自由文本，输出三组结构化实体 + 一个 JSON 串（{@code caption_mark} 字段直接落库）。
 * 前端无需再做字符串扫描即可渲染：点击 {@code @uid} 跳个人主页、{@code #topic} 跳话题聚合、
 * {@code [image:1:xxx.png]} 在描述里以图片卡片呈现。</p>
 *
 * <p><b>解析规则（骨架阶段）</b>：</p>
 * <ul>
 *   <li>{@code @\S+} —— 任意非空白串作为被 @ 的标识（uid 或昵称由业务方约定）</li>
 *   <li>{@code #\S+} —— 任意非空白串作为话题名</li>
 *   <li>{@code [image:idx:filename]} —— 描述内嵌图片引用（{@code idx} 1-based）</li>
 * </ul>
 *
 * <p>重复出现保留多次（不去重）；空 caption 返回空 mark；解析异常兜底为 {@code "{}"}，
 * 不阻断上传主流程。</p>
 */
@Component
public class CaptionMarkParser {

    private static final Pattern MENTION = Pattern.compile("@(\\S+)");
    private static final Pattern HASHTAG = Pattern.compile("#(\\S+)");
    private static final Pattern IMAGE_REF = Pattern.compile("\\[image:(\\d+):([^\\]]+)]");

    private final ObjectMapper objectMapper;

    public CaptionMarkParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 caption 文本。
     *
     * @param text 原始 caption（可为 null / 空）
     * @return 解析结果（含三类实体列表 + 落库的 mark JSON）
     */
    public ParseResult parse(String text) {
        if (text == null || text.isEmpty()) {
            return ParseResult.empty();
        }
        List<String> mentions = new ArrayList<>();
        List<String> hashtags = new ArrayList<>();
        List<ImageRef> images = new ArrayList<>();

        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            mentions.add(m.group(1));
        }
        m = HASHTAG.matcher(text);
        while (m.find()) {
            hashtags.add(m.group(1));
        }
        m = IMAGE_REF.matcher(text);
        while (m.find()) {
            try {
                images.add(new ImageRef(Integer.parseInt(m.group(1)), m.group(2)));
            } catch (NumberFormatException ignore) {
                // 非法 idx 跳过
            }
        }

        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("mentions", mentions);
        mark.put("hashtags", hashtags);
        mark.put("images", images);
        String json;
        try {
            json = objectMapper.writeValueAsString(mark);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        return new ParseResult(mentions, hashtags, images, json);
    }

    /** 描述内嵌图片引用。 */
    public record ImageRef(int idx, String filename) {
    }

    /** 解析结果。 */
    public record ParseResult(List<String> mentions,
                              List<String> hashtags,
                              List<ImageRef> images,
                              String markJson) {
        public static ParseResult empty() {
            return new ParseResult(List.of(), List.of(), List.of(), "{}");
        }
    }
}
