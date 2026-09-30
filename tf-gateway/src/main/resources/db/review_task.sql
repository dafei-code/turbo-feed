-- =============================================================================
-- 复审任务表 review_task（单表，ds_0 = turbo_feed_1）
-- -----------------------------------------------------------------------------
-- 抖音式「举报累计 → 人工复核」的承接结构：当同一内容的待处理举报数达到阈值，
-- MediaReviewService#report 自动建一条 PENDING 复审任务，归 REVIEWER 二次研判。
--
-- ⚠️ 单表：必须在 shardingsphere-config.yaml / shardingsphere-config-local.yml 的
--    `!SINGLE` 里登记 ds_0.review_task（否则查询抛 TableNotFoundException）。
-- ⚠️ 生产绝不可跑 init-local.sql（会 DROP 两库重建）；本脚本仅增量建表。
--    若表已存在，IF NOT EXISTS 保证幂等。
-- =============================================================================
CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`review_task` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务ID(自增, 单表队列)',
  `media_id`      VARCHAR(255) NOT NULL                COMMENT '被举报内容标识(帖代表媒体ID)',
  `author_id`     BIGINT       NOT NULL                COMMENT '内容作者 userId',
  `task_type`     VARCHAR(32)  NOT NULL                COMMENT '触发类型: REPORT_ACCUMULATED',
  `trigger_count` INT          NOT NULL DEFAULT 0      COMMENT '触发时的待处理举报数',
  `status`        TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待复审 1=无违规(维持发布) 2=确认违规(已下架)',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建单时间',
  `resolved_at`   DATETIME     NULL                    COMMENT '处置时间',
  `resolver`      VARCHAR(64)  NULL                    COMMENT '处置人(REVIEWER/ADMIN 的 userId)',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_media` (`media_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报累计复审任务队列(单表 ds_0, 抖音式复审闭环)';
