package com.turbofeed.gateway.service.moderation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 敏感词条目（{@code sensitive_word} 表的行模型）。
 *
 * @param id        主键
 * @param word      敏感词
 * @param category  分类（POLITICS/PORN/VIOLENCE/AD/DEFAULT，骨架阶段不分类扫描，保留扩展点）
 * @param enabled   是否启用（软删除/临时下线）
 * @param revision  修订号（写操作 +1，审计用；骨架阶段不参与变更检测，由 checkSum 完成）
 */
public record SensitiveWord(
        @JsonProperty("id") long id,
        @JsonProperty("word") String word,
        @JsonProperty("category") String category,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("revision") long revision) {
}
