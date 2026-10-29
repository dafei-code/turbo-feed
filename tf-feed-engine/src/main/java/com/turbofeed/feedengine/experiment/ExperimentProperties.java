package com.turbofeed.feedengine.experiment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A/B 实验配置（最简分桶框架，P0-1b）。
 *
 * <p>按<b>召回通道名</b>（{@code HOT}/{@code INTEREST}/{@code CF}/{@code VECTOR}/{@code FOLLOW}，
 * 即 {@code RecallType.name()}）配置覆写：命中分桶的用户用 {@code ratio} 替换该通道默认配比，
 * 其余用户维持默认路径。默认整体关闭（fail-open）。</p>
 *
 * <p>示例（application.yml）：
 * <pre>
 * turbofeed:
 *   experiment:
 *     enabled: true
 *     overrides:
 *       CF:
 *         buckets: "0-49"   # 前 50% 用户
 *         ratio: 0.2        # item-CF 召回占比提至 20%
 * </pre>
 * 即：把 50% 用户当作实验组（CF 占比 20%），另 50% 为对照组（CF 占比默认 10%）；
 * 下游按 {@link ExperimentBucket#of(String)} 对 userId 重新分桶即可做效果对照。</p>
 *
 * <p>无 Lombok：显式 getter/setter（与本模块既有 Properties 类一致）。</p>
 */
@Component
@ConfigurationProperties(prefix = "turbofeed.experiment")
public class ExperimentProperties {

    /** 实验总开关（默认关：全量走默认配比，覆写不生效）。 */
    private boolean enabled = false;

    /** 各召回通道的覆写：key = 通道名（RecallType.name()），value = 桶规格 + 覆写配比。 */
    private Map<String, BucketOverride> overrides = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, BucketOverride> getOverrides() {
        return overrides;
    }

    public void setOverrides(Map<String, BucketOverride> overrides) {
        this.overrides = overrides;
    }

    /**
     * 计算某用户在某通道的最终配比：命中覆写则返回 {@code override.ratio}，否则返回 {@code baseRatio}。
     * 纯函数（不依赖 Spring 上下文），便于单测；任何异常分支都回退 {@code baseRatio}（fail-open）。
     *
     * @param recallTypeName 召回通道名（RecallType.name()）
     * @param userId         用户标识（null → 回退默认配比，匿名不走实验）
     * @param baseRatio      该通道默认配比
     * @return 最终配比（&gt; 0 且 &le; 1）
     */
    public double resolveRatio(String recallTypeName, String userId, double baseRatio) {
        if (!enabled || userId == null) {
            return baseRatio;
        }
        BucketOverride o = overrides.get(recallTypeName);
        if (o == null || o.getRatio() <= 0d) {
            return baseRatio;
        }
        Set<Integer> buckets = ExperimentBucket.parseBuckets(o.getBuckets(), ExperimentBucket.DEFAULT_BUCKETS);
        if (buckets.isEmpty() || buckets.contains(ExperimentBucket.of(userId))) {
            return o.getRatio();
        }
        return baseRatio;
    }

    /** 单通道覆写：在指定桶范围内用 {@link #ratio} 替换默认召回占比。 */
    public static class BucketOverride {
        /** 桶规格："0-49" / "0,10,20" / "5"；空 = 对所有桶生效。 */
        private String buckets = "";
        /** 覆写后的召回占比（0 &lt; ratio &le; 1）。 */
        private double ratio = 0d;

        public String getBuckets() {
            return buckets;
        }

        public void setBuckets(String buckets) {
            this.buckets = buckets;
        }

        public double getRatio() {
            return ratio;
        }

        public void setRatio(double ratio) {
            this.ratio = ratio;
        }
    }
}
