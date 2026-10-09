-- 举报人信用表（P1 #131，单表落 ds_0 = turbo_feed_1）
-- 与 account_health / review_task 同套路：经 ShardingSphere !SINGLE 托管，DDL 增量、幂等。
-- 运行：mysql -uroot -p123456 < tf-gateway/src/main/resources/db/reporter_credit.sql
CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`reporter_credit` (
  `user_id`        BIGINT       NOT NULL,
  `total_reports`  INT          NOT NULL DEFAULT 0 COMMENT '累计举报次数',
  `upheld`         INT          NOT NULL DEFAULT 0 COMMENT '举报被确认成立次数',
  `rejected`       INT          NOT NULL DEFAULT 0 COMMENT '举报被驳回次数',
  `credibility`    INT          NOT NULL DEFAULT 100 COMMENT '举报人信用分(0~100, 初始100无罪推定)',
  `last_report_at` DATETIME     NULL     COMMENT '最后一次举报时间',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  KEY `idx_credibility` (`credibility`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报人信用表(单表 ds_0, P1 #131 举报人信用+恶意举报防御)';
