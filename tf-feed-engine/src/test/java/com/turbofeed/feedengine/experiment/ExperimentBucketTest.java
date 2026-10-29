package com.turbofeed.feedengine.experiment;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExperimentBucket} 纯函数单测（无中间件、无 Spring）。
 */
class ExperimentBucketTest {

    @Test
    void of_null_returnsZero() {
        assertEquals(0, ExperimentBucket.of(null));
        assertEquals(0, ExperimentBucket.of(null, 100));
    }

    @Test
    void of_deterministicAndInRange() {
        String u = "user-12345";
        int b1 = ExperimentBucket.of(u);
        int b2 = ExperimentBucket.of(u);
        assertEquals(b1, b2, "同一 userId 必须稳定映射到同一桶");
        assertTrue(b1 >= 0 && b1 < ExperimentBucket.DEFAULT_BUCKETS);
    }

    @Test
    void of_customBucketCount() {
        String u = "user-xyz";
        int buckets = 16;
        int b = ExperimentBucket.of(u, buckets);
        assertTrue(b >= 0 && b < buckets);
        // 不同 bucket 基数一般落到不同桶（至少稳定）
        assertEquals(b, ExperimentBucket.of(u, buckets));
    }

    @Test
    void of_invalidBucketCount_fallsBackToDefault() {
        // buckets <= 0 → 回退默认 100 桶，仍在合法范围
        int b = ExperimentBucket.of("u", 0);
        assertTrue(b >= 0 && b < ExperimentBucket.DEFAULT_BUCKETS);
        int bNeg = ExperimentBucket.of("u", -5);
        assertTrue(bNeg >= 0 && bNeg < ExperimentBucket.DEFAULT_BUCKETS);
    }

    @Test
    void parseBuckets_range() {
        Set<Integer> s = ExperimentBucket.parseBuckets("0-49", 100);
        assertEquals(50, s.size());
        assertTrue(s.contains(0));
        assertTrue(s.contains(49));
        assertFalse(s.contains(50));
    }

    @Test
    void parseBuckets_discrete() {
        Set<Integer> s = ExperimentBucket.parseBuckets("0,10,20", 100);
        assertEquals(3, s.size());
        assertTrue(s.contains(0));
        assertTrue(s.contains(10));
        assertTrue(s.contains(20));
    }

    @Test
    void parseBuckets_single() {
        Set<Integer> s = ExperimentBucket.parseBuckets("5", 100);
        assertEquals(Set.of(5), s);
    }

    @Test
    void parseBuckets_emptySpec_returnsEmpty() {
        // 空规格 = 空集，语义上"对所有桶生效"（调用方据此判空放行）
        assertTrue(ExperimentBucket.parseBuckets("", 100).isEmpty());
        assertTrue(ExperimentBucket.parseBuckets(null, 100).isEmpty());
        assertTrue(ExperimentBucket.parseBuckets("   ", 100).isEmpty());
    }

    @Test
    void parseBuckets_outOfRange_dropped() {
        // 越界值被丢弃，只保留 [0, buckets) 内的值
        Set<Integer> s = ExperimentBucket.parseBuckets("0-200", 100);
        assertEquals(100, s.size());
        assertFalse(s.contains(200));
        assertFalse(s.contains(100));
    }

    @Test
    void parseBuckets_invalidSegment_ignored_failOpen() {
        Set<Integer> s = ExperimentBucket.parseBuckets("abc,5,", 100);
        assertEquals(Set.of(5), s, "非法段静默忽略，合法段仍生效");
    }

    @Test
    void parseBuckets_reversedRange_empty() {
        // lo > hi → 空集（fail-open，不抛）
        assertTrue(ExperimentBucket.parseBuckets("10-5", 100).isEmpty());
    }
}
