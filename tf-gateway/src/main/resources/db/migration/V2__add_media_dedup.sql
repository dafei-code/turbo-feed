-- =============================================================================
-- (#6 上传去重) 版本化「单表」：media_dedup（内容指纹表）
-- -----------------------------------------------------------------------------
-- 设计要点（与 V1 同构）：
--   · 单表落物理库 ds_0（turbo_feed_1），由 spring.flyway.url 直连绕过 ShardingSphere。
--   · CREATE TABLE IF NOT EXISTS + 使用「当前 schema」（不硬编码库名），满足幂等与可移植。
--   · 现有库（已跑过 V1）幂等无操作；全新库也能建出该表，满足「克隆即跑 + 版本化」。
--
-- 去重模型（per-user v1）：
--   · media_hash = 内容 SHA-256(hex)，全局唯一内容指纹。
--   · 去重维度默认按用户（uk_user_hash = (user_id, media_hash)），避免跨用户对象共享删除耦合
--     （同用户删原帖才影响副本，符合 per-user 口径；跨用户复用造成的「删 A 图 B 裂」在 v1 不做）。
--   · canonical_media_id / canonical_url = 首次落库的代表媒体（对象存储键 + 公开 URL）；
--     命中时新帖生成「别名 mediaId」指向该 URL，跳过对象存储写入 → 省空间。
--   · GLOBAL 维度（turbofeed.media.dedup.scope=GLOBAL）仅靠 idx_hash 点查复用，
--     本表仍按用户记行（同一内容不同用户各一行），便于追溯归属。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `media_dedup` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT     COMMENT '主键',
  `user_id`           BIGINT        NOT NULL                    COMMENT '归属用户(per-user 去重维度)',
  `media_hash`        CHAR(64)      NOT NULL                    COMMENT '内容 SHA-256(hex), 全局唯一内容指纹',
  `canonical_media_id` VARCHAR(255) NOT NULL                    COMMENT '首次落库的代表 media_id(对象存储键)',
  `canonical_url`     VARCHAR(1024) NOT NULL DEFAULT ''         COMMENT '首次落库的公开 URL(去重命中复用)',
  `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次记录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_hash` (`user_id`, `media_hash`),
  KEY `idx_hash` (`media_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传内容去重指纹表(单表 ds_0, per-user SHA-256)';
