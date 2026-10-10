-- 交互行为事件表（A1 真双塔召回的训练样本底座）
-- 单表落 ds_0 = turbo_feed_1，经 ShardingSphere !SINGLE 托管（见 shardingsphere-config(-128).yaml）。
-- 只写多、读少：追加流，纯 INSERT，created_at 由 DB 默认。
-- 与 behavior_log 的区别：behavior_log 记「审核异常行为」（举报/评论异常/处置），
-- 本表记「用户对内容的互动反馈」（曝光/观看/完播/点赞/评论/分享/不感兴趣），
-- 是 (user_id, item_id, event_type) 训练三元组的来源——离线双塔据此学用户×内容协同信号。
CREATE TABLE IF NOT EXISTS interaction_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  item_id VARCHAR(255) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  position INT DEFAULT NULL,
  page INT DEFAULT NULL,
  channel VARCHAR(32) DEFAULT NULL,
  pool TINYINT DEFAULT NULL,
  request_id VARCHAR(64) DEFAULT NULL,
  ext VARCHAR(512) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_item (user_id, item_id),
  KEY idx_type (event_type),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
