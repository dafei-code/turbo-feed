package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 全局举报水位服务（P1 #132 阈值波动：动态因子）。
 *
 * <p>维护「近窗口举报总量」这一全局水位指标（Redis 固定窗口 {@code INCR} + 过期），
 * 并据此把「举报累计复审升级阈值」从写死值浮动为动态值：</p>
 * <ul>
 *   <li><b>举报平静</b>（总量 ≤ calm）→ 阈值放宽（乘数 1.5）：减少误审、降低人工台压力；</li>
 *   <li><b>举报正常</b>（calm &lt; 总量 ≤ busy）→ 阈值不变（乘数 1.0）；</li>
 *   <li><b>举报繁忙</b>（busy &lt; 总量 ≤ surge）→ 阈值收紧（乘数 0.7）：更快捕获潜在违规爆发；</li>
 *   <li><b>举报峰涌</b>（总量 &gt; surge）→ 阈值大幅收紧（乘数 0.4）：配合 #131 信用过滤防止审核台被冲垮。</li>
 * </ul>
 *
 * <p><b>语义</b>：举报越多 → 单条内容越容易触发复审（更敏感）。与 #131 的「举报人信用过滤」互补——
 * 信用过滤负责挡恶意举报人，水位因子负责调总量敏感度弹性，两者解耦、互不替代。</p>
 *
 * <p><b>fail-open</b>：Redis 抖动或读取失败时，{@link #currentReportThreshold()} 回落基准阈值
 * {@code reportReReviewThreshold}，绝不阻断举报/复审主流程；{@link #recordReport()} 异常仅记日志。</p>
 *
 * <p><b>窗口近似</b>：采用 Redis 固定窗口（计数键 1h 过期，期间持续有举报则续期），非精确滑动窗口——
 * 属可接受的近似（与 #131 频控同源思路），调参不发黑。</p>
 */
@Service
public class ReviewWaterLevelService {

    private static final Logger log = LoggerFactory.getLogger(ReviewWaterLevelService.class);

    /** 近窗口举报总量键（全局维度，不区分内容/举报人）。 */
    private static final String WATER_KEY = "tf:report:waterlevel:total";

    private final StringRedisTemplate redisTemplate;
    private final MediaProperties properties;

    public ReviewWaterLevelService(StringRedisTemplate redisTemplate, MediaProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 每次举报计一次（fail-open）。固定窗口：首次写入设过期，期间持续有举报则续期，
     * 窗口内无举报后计数自然清零（水位回落）。
     */
    public void recordReport() {
        MediaProperties.Review r = properties.getReview();
        try {
            Long n = redisTemplate.opsForValue().increment(WATER_KEY);
            if (n != null && n == 1) {
                redisTemplate.expire(WATER_KEY, Duration.ofSeconds(r.getWaterLevelWindowSeconds()));
            }
        } catch (Exception e) {
            log.warn("举报水位计数失败（fail-open，不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 当前复审升级阈值：随近窗口举报总量浮动。Redis 异常 → 返回基准阈值（fail-open）。
     *
     * <p>动态阈值 = clamp(round(base × multiplier), min, max)，multiplier 由水位等级决定。</p>
     */
    public int currentReportThreshold() {
        MediaProperties.Review r = properties.getReview();
        int base = r.getReportReReviewThreshold();
        try {
            String v = redisTemplate.opsForValue().get(WATER_KEY);
            long total = (v == null || v.isBlank()) ? 0L : Long.parseLong(v);
            double multiplier = multiplierFor(total, r);
            int raw = (int) Math.round(base * multiplier);
            return clamp(raw, r.getReportThresholdMin(), r.getReportThresholdMax());
        } catch (Exception e) {
            log.warn("读取举报水位失败，回落基准阈值（fail-open）: {}", e.getMessage());
            return base;
        }
    }

    /** 水位等级 → 阈值乘数（爆量收紧 / 平稳放宽）。 */
    private double multiplierFor(long total, MediaProperties.Review r) {
        if (total <= r.getWaterLevelCalm()) {
            return 1.5;            // 平静：放宽，减少误审
        }
        if (total <= r.getWaterLevelBusy()) {
            return 1.0;           // 正常：基准
        }
        if (total <= r.getWaterLevelSurge()) {
            return 0.7;           // 繁忙：收紧
        }
        return 0.4;               // 峰涌：大幅收紧
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
