-- =============================================================================
-- V3 内容安全命中审计表（content_moderation_hit）
-- -----------------------------------------------------------------------------
-- 目的：把「拦了什么、为什么拦、人审结论如何」从 Prometheus 计数器升级为可回扫的
--       结构化样本。是反馈闭环的数据底座——误杀样本进白名单候选，漏放样本进坏样本库。
--
-- 设计要点：
--   · 单表落 ds_0(turbo_feed_1)，经 ShardingSphere !SINGLE 托管（两个 yaml 都要登记）。
--   · 只写多读少的追加流：写路径每次 BLOCK/REVIEW/LIMIT 命中插一行；PASS 不记。
--   · 脱敏：snippet_masked 只存命中区间打码后的短片段，不存原文全量（合规 + 防泄露）。
--   · 不阻塞主路径：写失败只告警（fail-open），见 ContentSecurityService。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `content_moderation_hit` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `scene`           VARCHAR(16)  NOT NULL                COMMENT '场景: NICKNAME/CAPTION/COMMENT/MEDIA',
  `user_id`         BIGINT       NOT NULL                COMMENT '提交者用户ID',
  `target_id`       VARCHAR(64)           DEFAULT NULL   COMMENT '关联对象ID(commentId/mediaId)',
  `snippet_masked`  VARCHAR(255)          DEFAULT NULL   COMMENT '脱敏片段(命中区间打码)',
  `matched_word`    VARCHAR(64)           DEFAULT NULL   COMMENT '命中词(规则层); 语义层未命中为 NULL',
  `category`        VARCHAR(32)           DEFAULT NULL   COMMENT '命中分类',
  `action`          VARCHAR(16)  NOT NULL                COMMENT '最终动作: BLOCK/REVIEW/LIMIT',
  `review_task_id`  BIGINT                DEFAULT NULL   COMMENT '关联人审任务ID(REVIEW 时)',
  `reviewer_result` VARCHAR(16)           DEFAULT NULL   COMMENT '人审结论: CONFIRMED/REJECTED/NULL未审',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `reviewed_at`     DATETIME              DEFAULT NULL   COMMENT '人审完成时间',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_action_created` (`action`, `created_at`),
  KEY `idx_reviewer_result` (`reviewer_result`),
  KEY `idx_target` (`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容安全命中审计(单表, 反馈闭环样本)';
