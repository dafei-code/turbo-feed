package com.turbofeed.gateway.service.review;

import com.turbofeed.gateway.config.MediaProperties;
import com.turbofeed.gateway.service.penalty.ViolationSeverity;
import com.turbofeed.gateway.service.penalty.ViolationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 梯度处置策略（来源:严重度 → {@link Disposition}）。
 *
 * <h3>默认矩阵（保持现有下架语义、零行为回归）</h3>
 * HIGH / CRITICAL / MID → {@link Disposition#INTERCEPT}（下架+扣分+处罚）；
 * LOW → {@link Disposition#MONITOR}（限公域）。所有现有违规路径都是 MID/HIGH，故默认下全部走
 * INTERCEPT，既有 e2e 全绿。
 *
 * <h3>可配置覆盖</h3>
 * {@code turbofeed.media.review.disposition-matrix} 支持按 {@code "来源:严重度"} → 处置名覆盖默认
 * （如 {@code POOL_PROMOTED:HIGH=MONITOR} 把流量池晋级复扫命中改为软处置）。无需改码即可灰度调整
 * 某类违规的处置力度，对齐抖音「按风险分级处置」。
 */
@Service
public class DispositionPolicy {

    /**
     * 显式声明日志（本机构建环境 Lombok 对新文件不生效）。
     */
    private static final Logger log = LoggerFactory.getLogger(DispositionPolicy.class);

    private final MediaProperties mediaProperties;

    public DispositionPolicy(MediaProperties mediaProperties) {
        this.mediaProperties = mediaProperties;
    }

    /**
     * 解析处置动作。
     *
     * @param source   违规来源
     * @param severity 严重度
     * @return 处置动作；配置非法值回落默认
     */
    public Disposition resolve(ViolationSource source, ViolationSeverity severity) {
        Map<String, String> matrix = mediaProperties.getReview().getDispositionMatrix();
        if (matrix != null) {
            String overridden = matrix.get(source.name() + ":" + severity.name());
            if (overridden != null && !overridden.isBlank()) {
                try {
                    return Disposition.valueOf(overridden.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("处置矩阵配置非法值(忽略，回落默认): key={}:{}, value={}",
                            source.name(), severity.name(), overridden);
                }
            }
        }
        return switch (severity) {
            case CRITICAL, HIGH, MID -> Disposition.INTERCEPT;
            case LOW -> Disposition.MONITOR;
        };
    }
}
