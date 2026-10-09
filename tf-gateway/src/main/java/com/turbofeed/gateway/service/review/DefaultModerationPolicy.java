package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.penalty.ViolationCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 双严格度默认实现（feature-match M3）。
 *
 * <p><b>查表优先级</b>：类目(byCategory) &gt; 场景(byScenario) &gt; 来源(bySource) &gt; defaultStrictness。
 * 解析出的严格度 → {@code profiles} 表取阈值档案；任一环节缺失 → 回落 {@code STANDARD}
 * （passThreshold 取 {@code confidence-threshold}，与 M1 行为完全一致，零回归）。</p>
 *
 * <p><b>fail-open</b>：配置异常/空值一律回落 STANDARD，绝不静默放行公域。</p>
 */
@Component
public class DefaultModerationPolicy implements ModerationPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultModerationPolicy.class);

    private final MediaProperties.Review.ModerationPolicyConfig cfg;
    private final double standardPassThreshold;

    public DefaultModerationPolicy(MediaProperties mediaProperties) {
        this.cfg = mediaProperties.getReview().getModerationPolicy();
        this.standardPassThreshold = mediaProperties.getReview().getConfidenceThreshold();
    }

    @Override
    public StrictnessProfile resolve(ModerationMode source, ModerationScenario scenario, ViolationCategory category) {
        ModerationStrictness strictness = resolveStrictness(source, scenario, category);
        StrictnessProfile profile = lookup(strictness);
        if (profile == null) {
            // STANDARD 兜底：与 M1 全局阈值一致，未配置严格度矩阵时行为不变
            profile = new StrictnessProfile(standardPassThreshold);
        }
        log.debug("双严格度解析: source={}, scenario={}, category={} -> strictness={}, passThreshold={}",
                source, scenario, category, strictness, profile.getPassThreshold());
        return profile;
    }

    private ModerationStrictness resolveStrictness(ModerationMode source, ModerationScenario scenario, ViolationCategory category) {
        Map<ViolationCategory, ModerationStrictness> byCat = cfg.getByCategory();
        if (category != null && byCat != null && byCat.get(category) != null) {
            return byCat.get(category);
        }
        Map<ModerationScenario, ModerationStrictness> byScn = cfg.getByScenario();
        if (scenario != null && byScn != null && byScn.get(scenario) != null) {
            return byScn.get(scenario);
        }
        Map<ModerationMode, ModerationStrictness> bySrc = cfg.getBySource();
        if (source != null && bySrc != null && bySrc.get(source) != null) {
            return bySrc.get(source);
        }
        ModerationStrictness d = cfg.getDefaultStrictness();
        return d != null ? d : ModerationStrictness.STANDARD;
    }

    private StrictnessProfile lookup(ModerationStrictness strictness) {
        Map<ModerationStrictness, StrictnessProfile> profiles = cfg.getProfiles();
        if (profiles == null) {
            return null;
        }
        return profiles.get(strictness);
    }
}
