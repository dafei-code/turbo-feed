-- 用户长期/短期兴趣标签持久化表（抖音式「用户画像」耐久层 + 离线特征源）
-- 单表落 ds_0 = turbo_feed_1，经 ShardingSphere !SINGLE 托管（见 shardingsphere-config(-128).yaml）。
-- 画像主计算在 feed-engine（Redis Hash tf:user:interest:* / tf:user:interest:recent:*，半衰期衰减），
-- 但引擎无 DataSource；故由网关定时 SCAN 这些 Redis 键、upsert 到本表作耐久/离线特征汇。
-- 一张表同时承载长期层(layer='long')与短期层(layer='short')：同一 (user_id,tag) 两层各一行。
-- 引擎冷启动回灌本表需给引擎加 DB（更大改动），本期不做；本表当前用于：
--   ① 画像耐久备份（Redis 重启不丢长期兴趣）② 离线训练/A1 直接读用户长期兴趣特征。
CREATE TABLE IF NOT EXISTS interest_tag (
  user_id BIGINT NOT NULL,
  tag VARCHAR(128) NOT NULL,
  weight DOUBLE NOT NULL,
  layer VARCHAR(8) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, tag, layer),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
