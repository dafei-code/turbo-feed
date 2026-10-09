-- 异常行为日志表（P2-2，抖音式审核闭环数据底座）
-- 单表落 ds_0 = turbo_feed_1，经 ShardingSphere !SINGLE 托管（见 shardingsphere-config-128.yaml）。
-- 只写多、读少：追加流，纯 INSERT，created_at 由 DB 默认。
CREATE TABLE IF NOT EXISTS behavior_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  media_id VARCHAR(255) DEFAULT NULL,
  action VARCHAR(32) NOT NULL,
  detail VARCHAR(512) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user (user_id),
  KEY idx_action (action),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
