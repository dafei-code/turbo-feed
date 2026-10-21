package com.turbofeed.shared.caption;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CaptionTagParser} 隔离单测（tf-shared 首个测试，纯静态工具、零依赖）。
 *
 * <p>覆盖话题提取的归一化（小写）、去重（保首次顺序）、单条上限截断、上限=0 不限、
 * 中文/连字符话题词、以及「重复标签不占上限名额」这一关键语义。</p>
 */
class CaptionTagParserTest {

    @Test
    void parse_null_returnsEmpty() {
        assertTrue(CaptionTagParser.parse(null).isEmpty());
    }

    @Test
    void parse_empty_returnsEmpty() {
        assertTrue(CaptionTagParser.parse("").isEmpty());
    }

    @Test
    void parse_noTags_returnsEmpty() {
        assertTrue(CaptionTagParser.parse("今天天气真好 没有话题").isEmpty());
    }

    @Test
    void parse_extractsAndLowercases() {
        // 大小写统一归一：#Java/#java 视为同一话题
        List<String> tags = CaptionTagParser.parse("喜欢 #Java 和 #Go 还有 #java 与 #Python");
        assertEquals(List.of("java", "go", "python"), tags);
    }

    @Test
    void parse_dedupPreservesFirstOccurrenceOrder() {
        List<String> tags = CaptionTagParser.parse("#a #b #a #c #b");
        assertEquals(List.of("a", "b", "c"), tags);
    }

    @Test
    void parse_defaultMaxTags_capsAt10() {
        // 15 个不同话题 → 仅保留前 10
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append("#t").append(i).append(" ");
        }
        List<String> tags = CaptionTagParser.parse(sb.toString());
        assertEquals(10, tags.size());
        assertEquals(List.of("t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"), tags);
    }

    @Test
    void parse_customMaxTags() {
        List<String> tags = CaptionTagParser.parse("#a #b #c #d", 2);
        assertEquals(List.of("a", "b"), tags);
    }

    @Test
    void parse_maxTagsZeroMeansUnlimited() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append("#t").append(i).append(" ");
        }
        List<String> tags = CaptionTagParser.parse(sb.toString(), 0);
        assertEquals(15, tags.size());
    }

    @Test
    void parse_duplicateDoesNotConsumeCapSlot() {
        // maxTags=2：重复 #a 不占名额 → 仍为 [a, b]（而非 [a]）
        List<String> tags = CaptionTagParser.parse("#a #a #b #c", 2);
        assertEquals(List.of("a", "b"), tags);
    }

    @Test
    void parse_chineseAndHyphenTopicWords() {
        // 中文话题词、间隔号 ·、连字符 - 均属合法话题字符集
        List<String> tags = CaptionTagParser.parse("推荐 #美食探店 与 #美妆·教程 还有 #vlog-日常");
        assertEquals(List.of("美食探店", "美妆·教程", "vlog-日常"), tags);
    }

    @Test
    void parse_trailingPunctuationNotAbsorbed() {
        // #a,b 不应把逗号吃进话题；#ab 不应跨标点粘连
        List<String> tags = CaptionTagParser.parse("#a, #b. #cd!");
        assertEquals(List.of("a", "b", "cd"), tags);
    }
}
