package com.turbofeed.gateway.service.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextNormalizer} 归一化的等价性与下标回溯护栏。
 *
 * <p><b>为什么需要这组测试</b>：Phase 1 把「逐字符 NFKC」优化为「恒等字符跳过 ICU」。
 * 这是一条热路径上的性能优化，一旦 {@code nfkcIdentity} 的判定边界写错
 * （比如误把某类兼容字符判成恒等），后果是<b>该折叠的没折叠</b>——
 * 敏感词因此漏放，而且从接口层面完全看不出异常（还是「静默失效」这一类风险）。
 * 所以必须有测试锁住「优化后的输出 == 逐字符 NFKC 的输出」。</p>
 *
 * <p>本类不依赖 Spring / DB / Redis，纯函数验证。</p>
 */
class TextNormalizerTest {

    /**
     * 参照实现：优化前的「逐字符 NFKC」逻辑，仅用于对拍。
     *
     * <p>刻意在测试里<b>独立重写一遍</b>（而不是调用被测方法），
     * 才有对拍价值——若两边共用同一段代码，这个测试就退化成恒真断言。</p>
     */
    private static String referenceNormalize(String raw, boolean stripSeparators, boolean foldTraditional) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (isInvisible(c)) {
                continue;
            }
            String folded = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
            for (int k = 0; k < folded.length(); k++) {
                char lc = folded.charAt(k);
                if (isInvisible(lc)) {
                    continue;
                }
                if (stripSeparators && !Character.isLetterOrDigit(lc)) {
                    continue;
                }
                char lower = Character.toLowerCase(lc);
                out.append(foldTraditional ? TRAD_MAP_REF.getOrDefault(lower, lower) : lower);
            }
        }
        return out.toString();
    }

    /** 测试内独立维护的少量繁简映射（覆盖断言用到的字，避免复制整张表）。 */
    private static final java.util.Map<Character, Character> TRAD_MAP_REF = java.util.Map.of(
            '賭', '赌', '博', '博', '黃', '黄', '測', '测', '試', '试',
            '與', '与', '語', '语', '體', '体', '繁', '繁', '簡', '简');

    private static boolean isInvisible(char c) {
        return c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u200E' || c == '\u200F'
                || (c >= '\u202A' && c <= '\u202E')
                || (c >= '\u2060' && c <= '\u2064')
                || c == '\uFEFF' || c == '\u00AD' || c == '\u180E';
    }

    // ==================== 等价性：优化后 == 逐字符 NFKC ====================

    @ParameterizedTest(name = "等价性: {0}")
    @ValueSource(strings = {
            "普通中文评论内容",
            "中英混排 mixed content 12345",
            "全角字符ＡＢＣ１２３",          // 全角 → 半角（NFKC 变化）
            "兼容符号㈱与㍿与①",              // 一变多 / 一变一（NFKC 变化）
            "连字ﬁ与罗马数字Ⅻ",              // 一变多
            "繁体賭博與測試",                  // 繁简折叠
            "夹杂零宽\u200B字符的文本",       // 不可见字符剥离
            "标点-穿插,的,文本",              // 分隔符剥离
            "Emoji😀与插件🧩混排",           // 代理对（不在优化区，走 ICU）
            "日本語かなカナとハングル한글",   // 非 CJK 基本区，走 ICU
    })
    @DisplayName("优化后的归一化输出必须与逐字符 NFKC 完全一致")
    void optimizedMatchesReference(String input) {
        for (boolean strip : new boolean[]{true, false}) {
            for (boolean trad : new boolean[]{true, false}) {
                String expected = referenceNormalize(input, strip, trad);
                String actual = TextNormalizer.normalize(input, strip, trad, false).text();
                assertEquals(expected, actual,
                        "stripSeparators=" + strip + ", foldTraditional=" + trad + " 时输出不一致");
            }
        }
    }

    // ==================== 下标回溯（高亮定位的正确性）====================

    @Test
    @DisplayName("剥离分隔符后，命中区间能正确回溯到原文（含被剥掉的分隔符）")
    void indexMapCoversStrippedSeparators() {
        String raw = "测 试-敏 感词";
        TextNormalizer.Normalized n = TextNormalizer.normalize(raw, true, false, false);
        // 归一化后 "测试敏感词" 的 [0,2) 应回溯到原文 [0,3)（覆盖 "测 试"）
        assertEquals(0, n.toOriginalIndex(0));
        assertEquals("测试敏感词", n.text());
        int startOrig = n.toOriginalIndex(0);
        int endOrig = n.toOriginalIndex(1);
        assertEquals(0, startOrig);
        assertEquals(2, endOrig, "第 2 个归一化字符（试）在原文下标 2");
    }

    @Test
    @DisplayName("一变多字符（㈱→(株)）的每个输出字符都指向同一个原文下标")
    void multiCharExpansionSharesOriginalIndex() {
        String raw = "㈱";
        TextNormalizer.Normalized n = TextNormalizer.normalize(raw, false, false, false);
        // NFKC 展开为 "(株)" 共 3 字符，全部指向原文下标 0
        assertEquals("(株)", n.text());
        for (int i = 0; i < n.text().length(); i++) {
            assertEquals(0, n.toOriginalIndex(i), "展开的第 " + i + " 个字符应指向原文下标 0");
        }
    }

    @Test
    @DisplayName("越界下标回溯返回 -1（不抛异常）")
    void outOfRangeIndexReturnsMinusOne() {
        TextNormalizer.Normalized n = TextNormalizer.normalize("abc", true, true, false);
        assertEquals(-1, n.toOriginalIndex(-1));
        assertEquals(-1, n.toOriginalIndex(999));
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("null / 空串返回空结果，不抛异常")
    void nullAndEmptyAreSafe() {
        assertEquals("", TextNormalizer.normalize(null, true, true, false).text());
        assertEquals("", TextNormalizer.normalize("", true, true, false).text());
    }

    @Test
    @DisplayName("整段都是被剥离字符时，归一化结果为空")
    void allStrippedYieldsEmpty() {
        assertEquals("", TextNormalizer.normalize(" \u200B-.,", true, false, false).text());
    }

    // ==================== 性能优化未改变安全语义 ====================

    @Test
    @DisplayName("汉字大文本归一化后内容不变（优化区覆盖验证）")
    void chineseTextUnchanged() {
        // 一段纯中文（全部落在 CJK 基本区，走快速路径）
        String raw = "这是一段用于验证归一化不改变中文内容的测试文本";
        TextNormalizer.Normalized n = TextNormalizer.normalize(raw, true, false, false);
        assertEquals(raw, n.text(), "纯中文文本归一化后应完全相同");
    }

    @Test
    @DisplayName("繁简折叠在快速路径下仍正确生效")
    void traditionalFoldingStillWorks() {
        TextNormalizer.Normalized n = TextNormalizer.normalize("賭博", true, true, false);
        assertEquals("赌博", n.text());
        // 关闭折叠时应保持原样
        assertEquals("賭博", TextNormalizer.normalize("賭博", true, false, false).text());
    }

    @Test
    @DisplayName("拼音折叠开启时追加序列，且原文汉字仍保留")
    void pinyinFoldAppendsSequence() {
        // 用映射表确实收录的字（黄/赌/毒 等涉敏高频字）
        TextNormalizer.Normalized n = TextNormalizer.normalize("黄赌毒", true, false, true);
        assertTrue(n.text().startsWith("黄赌毒"), "原文汉字必须保留，实际=" + n.text());
        assertTrue(n.text().contains("huang"), "应追加「黄」的拼音，实际=" + n.text());
        assertTrue(n.text().contains("du"), "应追加「赌/毒」的拼音，实际=" + n.text());
        // 分隔符 \u0001 的下标映射为 -1（不在原文中）
        int sepIdx = n.text().indexOf('\u0001');
        assertTrue(sepIdx > 0, "应有分隔符");
        assertEquals(-1, n.toOriginalIndex(sepIdx));
    }
}