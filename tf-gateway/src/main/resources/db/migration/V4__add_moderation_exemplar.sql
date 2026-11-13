-- =============================================================================
-- V4 内容安全坏样本表（moderation_exemplar）
-- -----------------------------------------------------------------------------
-- 目的：语义层的"已知违规样本库"。sidecar 加载这些样本的 embedding，对新文本做
--       近邻检索；与 content_moderation_hit 形成闭环——人审判 CONFIRMED 的漏放样本
--       补录进此表，模型下次就能挡住同类改写。
--
-- 设计要点：
--   · 单表落 ds_0，经 ShardingSphere !SINGLE 托管（两个 yaml 都要登记）。
--   · embedding 列先留 NULL：本地 dev 阶段 sidecar 用字符级近似/哈希占位，
--     不绑死某家模型；接真模型后再回填向量。
--   · enabled 软开关：误录样本可停用而不删，保留审计。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `moderation_exemplar` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `exemplar_text` VARCHAR(512) NOT NULL                COMMENT '坏样本原文（已知违规表达）',
  `category`      VARCHAR(32)  NOT NULL                COMMENT '分类: 广告/导流/涉黄/辱骂 等',
  `embedding`     BLOB                  DEFAULT NULL   COMMENT '向量(模型接入后回填)',
  `source`        VARCHAR(16)           DEFAULT 'MANUAL' COMMENT '来源: MANUAL/CONFIRMED_HIT',
  `enabled`       TINYINT      NOT NULL DEFAULT 1       COMMENT '1启用 0停用',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_category_enabled` (`category`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容安全坏样本库(语义层)';
