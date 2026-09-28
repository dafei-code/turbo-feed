package com.turbofeed.shared.caption;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * caption 中的 {@code #话题} 解析工具（网关/引擎共用，零三方依赖）。
 *
 * <p><b>规则</b>：
 * <ul>
 *   <li>匹配 {@code #[话题词]}，话题词由 Unicode 字母 / 数字 / 下划线 / 中文连字符（·、—）组成；</li>
 *   <li>统一小写归一化（保证 {@code #Java} 与 {@code #java} 视作同一话题）；</li>
 *   <li>去重并保留首次出现顺序（{@link LinkedHashSet}）；</li>
 *   <li>单条 caption 默认最多取前 {@value #DEFAULT_MAX_TAGS} 个话题，防刷屏。</li>
 * </ul>
 *
 * <p><b>与敏感词的边界</b>：本类只做"提取话题"这件事，不做任何内容安全判定——
 * 内容安全由专门的内容安全层负责（见引擎 content-safety 包）。</p>
 */
public final class CaptionTagParser {

    /** 单条 caption 的话题上限（防刷屏）。 */
    public static final int DEFAULT_MAX_TAGS = 10;

    /**
     * 话题词字符集：Unicode 字母 / 数字 + 下划线 + 常见中文连字符 / 间隔号。
     * 不含空白与标点，避免 {@code #a,b} 把逗号吃进话题、或 {@code #ab} 跨词粘连。
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("#([\\p{L}\\p{N}_·\\-]+)");

    private CaptionTagParser() {
        // 工具类禁止实例化
    }

    /**
     * 解析 caption 中的话题标签（使用 {@link #DEFAULT_MAX_TAGS} 上限）。
     *
     * @param caption 原始文案（{@code null}/空返回空列表，非 {@code null}）
     * @return 小写、去重、按出现顺序的话题列表；无命中返回空列表
     */
    public static List<String> parse(String caption) {
        return parse(caption, DEFAULT_MAX_TAGS);
    }

    /**
     * 解析 caption 中的话题标签，带单条上限。
     *
     * @param caption  原始文案（{@code null}/空返回空列表）
     * @param maxTags  单条最多保留话题数（{@code <=0} 视为不限制）
     * @return 小写、去重、按出现顺序的话题列表
     */
    public static List<String> parse(String caption, int maxTags) {
        if (caption == null || caption.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher m = TAG_PATTERN.matcher(caption);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            if (maxTags > 0 && seen.size() >= maxTags) {
                break; // 已达上限，停止扫描
            }
            String tag = m.group(1).toLowerCase();
            if (!tag.isEmpty()) {
                seen.add(tag);
            }
        }
        return new ArrayList<>(seen);
    }
}
