package com.turbofeed.gateway.service.moderation;

import java.time.Instant;

/**
 * 坏样本记录（moderation_exemplar 单表）。
 *
 * @param id            主键
 * @param exemplarText  坏样本原文（已知违规表达）
 * @param category      分类：广告/导流/涉黄/辱骂 等
 * @param source        来源：MANUAL / CONFIRMED_HIT
 * @param enabled       1启用 0停用
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record ModerationExemplar(long id, String exemplarText, String category,
                                 String source, boolean enabled,
                                 Instant createdAt, Instant updatedAt) {
}
