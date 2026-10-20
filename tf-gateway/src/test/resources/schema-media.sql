-- 集成测用：单张 media 表（与物理分片 turbo_feed_{1,2}.media_0..127 同构）。
-- 目的不是验证 ShardingSphere 的分片改写（那属框架层），而是验证仓库 SQL 本身：
-- 搜索回查依赖 listPostImages(user_id, post_id) 的「整帖 + seq 有序」语义。
CREATE TABLE IF NOT EXISTS media (
  media_id     VARCHAR(255) NOT NULL,
  user_id      BIGINT       NOT NULL,
  url          VARCHAR(512) NOT NULL,
  status       TINYINT      NOT NULL DEFAULT 0,
  media_type   VARCHAR(16)  NOT NULL DEFAULT 'IMAGE',
  caption      VARCHAR(2048) NOT NULL DEFAULT '',
  caption_mark VARCHAR(512) NOT NULL DEFAULT '',
  post_id      VARCHAR(255) NOT NULL DEFAULT '',
  seq          TINYINT      NOT NULL DEFAULT 0,
  file_size    BIGINT       NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  duration     INT          DEFAULT NULL,
  PRIMARY KEY (media_id),
  KEY idx_user_status (user_id, status),
  KEY idx_user_created (user_id, created_at),
  KEY idx_user_post (user_id, post_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
