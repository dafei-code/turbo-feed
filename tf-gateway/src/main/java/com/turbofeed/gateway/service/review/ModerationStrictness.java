package com.turbofeed.gateway.service.review;

/**
 * 机审严格度档位（feature-match M3，双严格度矩阵）。
 *
 * <p>STANDARD=现状全局阈值(=confidence-threshold, 默认 0.9)兜底；STRICT=从严(高置信度才自动放行)；
 * RELAXED=宽松(中等置信度即可放行)。矩阵按 (来源, 场景, 类目) 解析出严格度，再映射到阈值档案。</p>
 */
public enum ModerationStrictness {
    STANDARD,
    STRICT,
    RELAXED
}
