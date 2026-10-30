package com.turbofeed.gateway.service.moderation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AhoCorasickTest {

    @Test
    void allMatches_collectsEveryHitInScanOrder() {
        AhoCorasick ac = new AhoCorasick(List.of("ab", "bc", "abc"));
        List<AhoCorasick.Match> hits = ac.allMatches("abc");

        assertEquals(3, hits.size());
        // 扫描序：i=1 命中 "ab"；i=2 命中 "abc"（最长）并沿 fail 链命中更短词 "bc"
        assertEquals(new AhoCorasick.Match("ab", 0, 2), hits.get(0));
        assertEquals(new AhoCorasick.Match("abc", 0, 3), hits.get(1));
        assertEquals(new AhoCorasick.Match("bc", 1, 3), hits.get(2));
    }

    @Test
    void allMatches_nestedWordTriggersBothLongAndShort() {
        // "毛主席" 是词，且后缀 "主席" 也是词 → 同位置命中两个
        AhoCorasick ac = new AhoCorasick(List.of("毛主席", "主席"));
        List<AhoCorasick.Match> hits = ac.allMatches("毛主席");

        assertEquals(2, hits.size());
        assertEquals("毛主席", hits.get(0).word());
        assertEquals("主席", hits.get(1).word());
    }

    @Test
    void allMatches_emptyTextReturnsEmptyList() {
        AhoCorasick ac = new AhoCorasick(List.of("foo"));
        assertTrue(ac.allMatches("").isEmpty());
        assertTrue(ac.allMatches(null).isEmpty());
    }

    @Test
    void allMatches_noHitReturnsEmptyList() {
        AhoCorasick ac = new AhoCorasick(List.of("foo", "bar"));
        assertTrue(ac.allMatches("hello world").isEmpty());
    }

    @Test
    void firstMatchDetail_stillReturnsSingleForBackwardCompat() {
        AhoCorasick ac = new AhoCorasick(List.of("ab", "bc", "abc"));
        AhoCorasick.Match m = ac.firstMatchDetail("abc");
        assertEquals("ab", m.word()); // 首个命中（最早结束的那个）
    }
}
