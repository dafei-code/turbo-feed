package com.turbofeed.feedengine.experiment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ExperimentProperties#resolveRatio} 纯函数单测（无 Spring 上下文）。
 */
class ExperimentPropertiesTest {

    private ExperimentProperties build(boolean enabled, String buckets, double ratio) {
        ExperimentProperties p = new ExperimentProperties();
        p.setEnabled(enabled);
        ExperimentProperties.BucketOverride o = new ExperimentProperties.BucketOverride();
        o.setBuckets(buckets);
        o.setRatio(ratio);
        p.setOverrides(java.util.Map.of("CF", o));
        return p;
    }

    @Test
    void disabled_returnsBase() {
        ExperimentProperties p = build(false, "0-49", 0.2);
        assertEquals(0.1, p.resolveRatio("CF", "user-1", 0.1));
    }

    @Test
    void anonymousUser_returnsBase() {
        ExperimentProperties p = build(true, "0-49", 0.2);
        assertEquals(0.1, p.resolveRatio("CF", null, 0.1));
    }

    @Test
    void bucketMatch_appliesOverride() {
        ExperimentProperties p = build(true, "0-49", 0.2);
        // 找一个确实落在 [0,49] 的 userId
        String u = findInBucket("0-49");
        assertEquals(0.2, p.resolveRatio("CF", u, 0.1));
    }

    @Test
    void bucketMismatch_returnsBase() {
        ExperimentProperties p = build(true, "0-49", 0.2);
        // 找一个确实落在 [50,99] 的 userId
        String u = findInBucket("50-99");
        assertEquals(0.1, p.resolveRatio("CF", u, 0.1));
    }

    @Test
    void emptyBucketsSpec_appliesToAll() {
        ExperimentProperties p = build(true, "", 0.2);
        assertEquals(0.2, p.resolveRatio("CF", "any-user", 0.1));
    }

    @Test
    void unknownChannel_returnsBase() {
        ExperimentProperties p = build(true, "0-49", 0.2);
        assertEquals(0.15, p.resolveRatio("INTEREST", "user-1", 0.15));
    }

    @Test
    void zeroOrNegativeOverride_returnsBase() {
        ExperimentProperties p = build(true, "0-49", 0d);
        String u = findInBucket("0-49");
        assertEquals(0.1, p.resolveRatio("CF", u, 0.1));
    }

    /** 在给定桶规格内找一个确定命中的 userId（穷举小样本）。 */
    private String findInBucket(String spec) {
        java.util.Set<Integer> target = ExperimentBucket.parseBuckets(spec, ExperimentBucket.DEFAULT_BUCKETS);
        for (int i = 0; i < 100000; i++) {
            String u = "probe-" + i;
            if (target.contains(ExperimentBucket.of(u))) {
                return u;
            }
        }
        throw new IllegalStateException("找不到命中桶 " + spec + " 的 userId");
    }
}
