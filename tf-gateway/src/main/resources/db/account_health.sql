-- =============================================================================
-- 账号健康分表 account_health（单表，ds_0 = turbo_feed_1）
-- -----------------------------------------------------------------------------
-- P0-b「账号健康分 + 阶梯处置」的持久层。与 account_penalty（硬封禁）解耦：
--   - account_penalty 管「硬执行」（封禁/警告，直接阻断写能力）；
--   - account_health   管「软健康」（扣分→推荐降权/限投稿/限变现，归零才硬封）。
-- 两者不互相引用，避免把健康分塞进处罚表、或把封禁字段塞进健康表。
--
-- 阶梯（对齐抖音「先流量隔离，后硬性封禁」）：
--   score >= 80 : 健康，正常推荐与投稿
--   60 <= score < 80 : 推荐降权（recommendation_weight=0.5）
--   40 <= score < 60 : 限投稿（canSubmit=false）
--   score < 40       : 限变现（canMonetize=false）
--   score <= 0       : 封禁（isBanned=true，等同 account_penalty 的硬封）
--
-- ⚠️ 单表：必须在 shardingsphere-config.yaml / shardingsphere-config-128.yaml /
--    shardingsphere-config-local.yml 的 `!SINGLE` 里登记 ds_0.account_health
--    （否则查询抛 TableNotFoundException）。
-- ⚠️ 生产绝不可跑 init-local.sql（会 DROP 两库重建）；本脚本仅增量建表。
--    若表已存在，IF NOT EXISTS 保证幂等。
-- =============================================================================
CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`account_health` (
  `user_id`         BIGINT    NOT NULL                COMMENT '用户ID(与 account_penalty 同键, 单表 ds_0)',
  `score`           INT       NOT NULL DEFAULT 100    COMMENT '健康分 0~100, 100 满分, 归零即封禁',
  `violation_count` INT       NOT NULL DEFAULT 0      COMMENT '累计违规次数(扣分输入, 与 account_penalty 各自计数互不耦合)',
  `last_deduct_at`  DATETIME  NULL                    COMMENT '最近一次扣分时点',
  `last_recover_at` DATETIME  NULL                    COMMENT '最近一次加分恢复时点',
  `created_at`      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建行时间',
  `updated_at`      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  KEY `idx_score` (`score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号健康分表(单表 ds_0, P0-b 阶梯处置)';
