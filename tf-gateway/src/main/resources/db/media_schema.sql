-- =============================================================================
-- turbo-feed 媒体元数据表建表语句（分库分表，与 user 表同策略）
-- -----------------------------------------------------------------------------
-- 分片方案（与 shardingsphere-config.yaml 严格对应）：
--   逻辑表 media，分片键 user_id（归属用户 ID，与 user 表分片键 id 同值）
--   路由：db    = hash(user_id) % 2 -> ds_0(turbo_feed_1) / ds_1(turbo_feed_2)
--         table = hash(user_id) % 2 -> media_0 / media_1
--   物理表共 4 张：turbo_feed_1.media_0|media_1、turbo_feed_2.media_0|media_1
--
-- 建表前置：库已存在（turbo_feed_1 / turbo_feed_2 由 user_schema.sql 建立）。
--   每个库内执行对应的 media_0 / media_1（下表已按库分组给出）。
--
-- 设计要点：
--   1) 分片键固定 user_id：保证「我的上传」WHERE user_id=? 精准命中单分片，不广播；
--      与 user 表用同一 HASH_MOD 契约，同一用户的媒体与其 user 行落在同片（便于后续单元化）。
--   2) media_id 为业务主键（形如 media/{userId}/{uuid}.{ext}），不做分片键——
--      分片键必须是可路由列 user_id；按 media_id 查询时调用方需同时带 user_id 才精准。
--   3) status：0=PENDING 1=APPROVED 2=REJECTED，对应 MediaStatus 枚举；
--      上传即受理 PENDING，机审/人工审翻转后 APPROVED/REJECTED，前端仅展示 APPROVED。
--   4) idx_user_status / idx_user_created：支撑「我的上传」按状态过滤与按时间倒序。
--   5) media_type / file_size：预留字段，本期仅 IMAGE，后续扩视频不动表结构。
--
-- ⚠️ 连接账号：shardingsphere-config.yaml 中 ds_0/ds_1 的 password 当前为空。
--    若本地 MySQL root 设有密码，请先在 shardingsphere-config.yaml 的 password 处填入，
--    否则应用启动后首次访问 media 表会因认证失败连不上（之前全内存态未暴露此问题）。
-- =============================================================================

-- -------------------- 库 1：turbo_feed_1（ds_0）--------------------
CREATE TABLE `turbo_feed_1`.`media_0` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键, 形如 media/{userId}/{uuid}.{ext})',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键(与 user 表同键, 同一用户媒体同片)',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址(本地盘或对象存储URL)',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO(本期仅 IMAGE)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_0)';

CREATE TABLE `turbo_feed_1`.`media_1` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键, 形如 media/{userId}/{uuid}.{ext})',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键(与 user 表同键, 同一用户媒体同片)',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址(本地盘或对象存储URL)',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO(本期仅 IMAGE)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_1)';

-- -------------------- 库 2：turbo_feed_2（ds_1）--------------------
CREATE TABLE `turbo_feed_2`.`media_0` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键, 形如 media/{userId}/{uuid}.{ext})',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键(与 user 表同键, 同一用户媒体同片)',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址(本地盘或对象存储URL)',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO(本期仅 IMAGE)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_0)';

CREATE TABLE `turbo_feed_2`.`media_1` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键, 形如 media/{userId}/{uuid}.{ext})',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键(与 user 表同键, 同一用户媒体同片)',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址(本地盘或对象存储URL)',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO(本期仅 IMAGE)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_1)';
