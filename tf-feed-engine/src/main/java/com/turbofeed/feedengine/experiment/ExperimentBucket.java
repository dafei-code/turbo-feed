package com.turbofeed.feedengine.experiment;

import java.util.HashSet;
import java.util.Set;

/**
 * 用户稳定分桶工具（A/B 试错的"尺子"，P0-1b）。
 *
 * <p><b>用途</b>：把任意 userId 稳定映射到 [0, buckets) 的桶号，配合 {@link ExperimentProperties}
 * 对某一桶用户覆写推荐配比（如把 item-CF 召回占比从 10% 提到 20%），从而在不发版、不阻断默认路径的前提下
 * 做可控的实验对照。下游指标（behavior_log / interaction_event）按同一公式对 userId 重新分桶即可对齐分组，
 * 无需在链路里透传桶号。</p>
 *
 * <p><b>fail-open</b>：解析非法桶规格、userId 为 null 等都静默回退到默认桶（0），绝不抛异常、
 * 绝不影响匿名默认路径。</p>
 */
public final class ExperimentBucket {

    /** 默认分桶数：100 桶 → 每桶约 1% 流量，足够做 50/50 或 10/90 等常见对照。 */
    public static final int DEFAULT_BUCKETS = 100;

    private ExperimentBucket() {
    }

    /**
     * 把用户稳定映射到 [0, buckets) 区间的桶号。
     *
     * @param userId  用户标识（null → 回退 0，匿名走默认路径）
     * @param buckets 桶总数（必须 &gt; 0，否则回退 {@link #DEFAULT_BUCKETS}）
     * @return 桶号，[0, buckets)
     */
    public static int of(String userId, int buckets) {
        int n = (buckets <= 0) ? DEFAULT_BUCKETS : buckets;
        if (userId == null) {
            return 0;
        }
        return Math.floorMod(userId.hashCode(), n);
    }

    /** {@link #of(String, int)} 的默认 100 桶版本。 */
    public static int of(String userId) {
        return of(userId, DEFAULT_BUCKETS);
    }

    /**
     * 解析桶规格为桶号集合。支持三种写法，可混用、逗号分隔：
     * <ul>
     *   <li>{@code "0-49"} —— 闭区间 [0,49]</li>
     *   <li>{@code "0,10,20"} —— 离散枚举</li>
     *   <li>{@code "5"} —— 单桶</li>
     * </ul>
     * 越界段与非法段静默忽略（fail-open）。
     *
     * @param spec   桶规格（null/空 → 空集，语义上等于"不限定桶、对所有桶生效"）
     * @param buckets 桶总数（解析时越界值被丢弃）
     * @return 桶号集合（不含越界值）
     */
    public static Set<Integer> parseBuckets(String spec, int buckets) {
        Set<Integer> set = new HashSet<>();
        int n = (buckets <= 0) ? DEFAULT_BUCKETS : buckets;
        if (spec == null || spec.isBlank()) {
            return set;
        }
        for (String raw : spec.split(",")) {
            String seg = raw.trim();
            if (seg.isEmpty()) {
                continue;
            }
            int dash = seg.indexOf('-');
            if (dash > 0) {
                try {
                    int lo = Integer.parseInt(seg.substring(0, dash).trim());
                    int hi = Integer.parseInt(seg.substring(dash + 1).trim());
                    if (lo <= hi) {
                        for (int i = lo; i <= hi; i++) {
                            if (i >= 0 && i < n) {
                                set.add(i);
                            }
                        }
                    }
                } catch (NumberFormatException ignore) {
                    // fail-open：忽略非法区间段
                }
            } else {
                try {
                    int v = Integer.parseInt(seg);
                    if (v >= 0 && v < n) {
                        set.add(v);
                    }
                } catch (NumberFormatException ignore) {
                    // fail-open：忽略非法段
                }
            }
        }
        return set;
    }
}
