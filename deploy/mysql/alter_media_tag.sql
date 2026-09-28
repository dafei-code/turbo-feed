-- =============================================================================
-- media_tag 增量建表（128 物理分片，与 media 表同分布）
-- -----------------------------------------------------------------------------
-- 分片键 post_id（= 引擎 timelineKey），hashMod128；偶数下标落 turbo_feed_1(ds_0)、
-- 奇数下标落 turbo_feed_2(ds_1)，与 media_0..media_127 完全对齐。
-- 本脚本用 IF NOT EXISTS，可重复执行、不 DROP 任何表，适合在已有库上增量补齐 media_tag。
--
-- 用途：内容标签的持久冷存（Redis tf:tag:media / tf:media:tags 是读路径主索引，
--       本表为可重建的系统记录，见 changelog 0049 / 0051）。
-- 配合：shardingsphere-config-128.yaml 与 shardingsphere-config-local.yml 的 media_tag autoTable。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_0` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_0; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_1` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_1; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_2` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_2; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_3` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_3; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_4` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_4; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_5` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_5; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_6` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_6; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_7` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_7; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_8` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_8; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_9` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_9; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_10` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_10; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_11` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_11; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_12` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_12; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_13` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_13; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_14` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_14; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_15` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_15; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_16` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_16; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_17` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_17; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_18` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_18; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_19` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_19; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_20` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_20; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_21` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_21; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_22` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_22; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_23` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_23; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_24` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_24; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_25` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_25; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_26` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_26; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_27` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_27; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_28` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_28; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_29` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_29; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_30` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_30; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_31` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_31; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_32` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_32; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_33` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_33; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_34` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_34; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_35` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_35; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_36` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_36; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_37` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_37; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_38` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_38; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_39` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_39; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_40` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_40; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_41` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_41; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_42` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_42; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_43` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_43; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_44` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_44; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_45` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_45; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_46` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_46; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_47` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_47; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_48` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_48; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_49` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_49; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_50` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_50; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_51` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_51; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_52` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_52; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_53` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_53; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_54` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_54; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_55` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_55; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_56` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_56; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_57` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_57; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_58` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_58; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_59` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_59; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_60` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_60; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_61` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_61; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_62` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_62; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_63` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_63; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_64` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_64; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_65` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_65; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_66` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_66; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_67` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_67; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_68` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_68; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_69` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_69; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_70` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_70; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_71` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_71; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_72` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_72; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_73` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_73; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_74` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_74; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_75` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_75; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_76` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_76; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_77` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_77; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_78` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_78; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_79` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_79; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_80` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_80; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_81` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_81; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_82` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_82; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_83` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_83; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_84` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_84; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_85` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_85; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_86` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_86; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_87` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_87; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_88` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_88; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_89` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_89; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_90` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_90; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_91` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_91; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_92` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_92; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_93` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_93; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_94` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_94; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_95` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_95; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_96` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_96; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_97` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_97; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_98` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_98; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_99` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_99; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_100` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_100; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_101` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_101; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_102` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_102; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_103` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_103; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_104` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_104; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_105` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_105; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_106` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_106; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_107` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_107; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_108` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_108; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_109` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_109; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_110` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_110; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_111` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_111; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_112` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_112; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_113` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_113; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_114` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_114; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_115` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_115; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_116` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_116; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_117` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_117; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_118` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_118; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_119` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_119; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_120` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_120; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_121` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_121; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_122` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_122; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_123` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_123; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_124` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_124; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_125` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_125; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_tag_126` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_1.media_tag_126; 与 Redis tf:tag:media/* 同源, 可重建)';

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_tag_127` (
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '帖子/内容身份(timelineKey; 有 post_id 用 post_id, 历史单图回退 media_id); 分片键',
  `tag`        VARCHAR(128) NOT NULL                COMMENT '内容标签(#话题解析, 小写归一)',
  `weight`     DECIMAL(10,4) NOT NULL DEFAULT 1.0   COMMENT '标签权重(MVP 落库统一 1.0; 真实累积权重以 Redis tf:user:interest 为准)',
  `created_at` BIGINT        NOT NULL                COMMENT '写入时间(epoch millis)',
  PRIMARY KEY (`post_id`, `tag`),
  KEY `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签持久表(物理分片 turbo_feed_2.media_tag_127; 与 Redis tf:tag:media/* 同源, 可重建)';
