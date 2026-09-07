-- =============================================================================
-- turbo-feed 媒体元数据表【扩容版】建表语句：2 库 × 64 表 = 128 物理表
-- -----------------------------------------------------------------------------
-- 用途：承载百亿级媒体元数据行（单表约千万行，B+Tree 索引友好）。
-- 路由（与 shardingsphere-config-128.yaml 严格对应）：
--   db    = hash(user_id) % 2   -> ds_0(turbo_feed_1) / ds_1(turbo_feed_2)
--   table = hash(user_id) % 64  -> media_0 .. media_63
--
-- 切换步骤（见 docs/changelog/0019-three-flows-at-scale.md）：
--   1) 在本机 MySQL 执行本文件，建立 128 张物理表；
--   2) 将 application.yml 的 spring.datasource.url 指向 classpath:shardingsphere-config-128.yaml；
--   3) 因分片数变化（2->64），需做存量数据再平衡（双写/灰度迁移），勿直接切换避免路由错乱。
--
-- 读路径说明：公域发现流已改走 Redis 时间线（不扫分片库），故扩容主要服务
--   写入分布与个人中心「我的上传」（按 user_id 精准单分片）。
-- =============================================================================

-- -------------------- 库 turbo_feed_1（ds_0）--------------------
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

CREATE TABLE `turbo_feed_1`.`media_2` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_2)';

CREATE TABLE `turbo_feed_1`.`media_3` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_3)';

CREATE TABLE `turbo_feed_1`.`media_4` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_4)';

CREATE TABLE `turbo_feed_1`.`media_5` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_5)';

CREATE TABLE `turbo_feed_1`.`media_6` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_6)';

CREATE TABLE `turbo_feed_1`.`media_7` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_7)';

CREATE TABLE `turbo_feed_1`.`media_8` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_8)';

CREATE TABLE `turbo_feed_1`.`media_9` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_9)';

CREATE TABLE `turbo_feed_1`.`media_10` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_10)';

CREATE TABLE `turbo_feed_1`.`media_11` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_11)';

CREATE TABLE `turbo_feed_1`.`media_12` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_12)';

CREATE TABLE `turbo_feed_1`.`media_13` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_13)';

CREATE TABLE `turbo_feed_1`.`media_14` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_14)';

CREATE TABLE `turbo_feed_1`.`media_15` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_15)';

CREATE TABLE `turbo_feed_1`.`media_16` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_16)';

CREATE TABLE `turbo_feed_1`.`media_17` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_17)';

CREATE TABLE `turbo_feed_1`.`media_18` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_18)';

CREATE TABLE `turbo_feed_1`.`media_19` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_19)';

CREATE TABLE `turbo_feed_1`.`media_20` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_20)';

CREATE TABLE `turbo_feed_1`.`media_21` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_21)';

CREATE TABLE `turbo_feed_1`.`media_22` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_22)';

CREATE TABLE `turbo_feed_1`.`media_23` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_23)';

CREATE TABLE `turbo_feed_1`.`media_24` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_24)';

CREATE TABLE `turbo_feed_1`.`media_25` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_25)';

CREATE TABLE `turbo_feed_1`.`media_26` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_26)';

CREATE TABLE `turbo_feed_1`.`media_27` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_27)';

CREATE TABLE `turbo_feed_1`.`media_28` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_28)';

CREATE TABLE `turbo_feed_1`.`media_29` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_29)';

CREATE TABLE `turbo_feed_1`.`media_30` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_30)';

CREATE TABLE `turbo_feed_1`.`media_31` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_31)';

CREATE TABLE `turbo_feed_1`.`media_32` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_32)';

CREATE TABLE `turbo_feed_1`.`media_33` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_33)';

CREATE TABLE `turbo_feed_1`.`media_34` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_34)';

CREATE TABLE `turbo_feed_1`.`media_35` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_35)';

CREATE TABLE `turbo_feed_1`.`media_36` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_36)';

CREATE TABLE `turbo_feed_1`.`media_37` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_37)';

CREATE TABLE `turbo_feed_1`.`media_38` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_38)';

CREATE TABLE `turbo_feed_1`.`media_39` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_39)';

CREATE TABLE `turbo_feed_1`.`media_40` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_40)';

CREATE TABLE `turbo_feed_1`.`media_41` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_41)';

CREATE TABLE `turbo_feed_1`.`media_42` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_42)';

CREATE TABLE `turbo_feed_1`.`media_43` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_43)';

CREATE TABLE `turbo_feed_1`.`media_44` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_44)';

CREATE TABLE `turbo_feed_1`.`media_45` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_45)';

CREATE TABLE `turbo_feed_1`.`media_46` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_46)';

CREATE TABLE `turbo_feed_1`.`media_47` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_47)';

CREATE TABLE `turbo_feed_1`.`media_48` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_48)';

CREATE TABLE `turbo_feed_1`.`media_49` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_49)';

CREATE TABLE `turbo_feed_1`.`media_50` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_50)';

CREATE TABLE `turbo_feed_1`.`media_51` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_51)';

CREATE TABLE `turbo_feed_1`.`media_52` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_52)';

CREATE TABLE `turbo_feed_1`.`media_53` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_53)';

CREATE TABLE `turbo_feed_1`.`media_54` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_54)';

CREATE TABLE `turbo_feed_1`.`media_55` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_55)';

CREATE TABLE `turbo_feed_1`.`media_56` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_56)';

CREATE TABLE `turbo_feed_1`.`media_57` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_57)';

CREATE TABLE `turbo_feed_1`.`media_58` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_58)';

CREATE TABLE `turbo_feed_1`.`media_59` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_59)';

CREATE TABLE `turbo_feed_1`.`media_60` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_60)';

CREATE TABLE `turbo_feed_1`.`media_61` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_61)';

CREATE TABLE `turbo_feed_1`.`media_62` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_62)';

CREATE TABLE `turbo_feed_1`.`media_63` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_63)';

-- -------------------- 库 turbo_feed_2（ds_1）--------------------
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

CREATE TABLE `turbo_feed_2`.`media_2` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_2)';

CREATE TABLE `turbo_feed_2`.`media_3` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_3)';

CREATE TABLE `turbo_feed_2`.`media_4` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_4)';

CREATE TABLE `turbo_feed_2`.`media_5` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_5)';

CREATE TABLE `turbo_feed_2`.`media_6` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_6)';

CREATE TABLE `turbo_feed_2`.`media_7` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_7)';

CREATE TABLE `turbo_feed_2`.`media_8` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_8)';

CREATE TABLE `turbo_feed_2`.`media_9` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_9)';

CREATE TABLE `turbo_feed_2`.`media_10` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_10)';

CREATE TABLE `turbo_feed_2`.`media_11` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_11)';

CREATE TABLE `turbo_feed_2`.`media_12` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_12)';

CREATE TABLE `turbo_feed_2`.`media_13` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_13)';

CREATE TABLE `turbo_feed_2`.`media_14` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_14)';

CREATE TABLE `turbo_feed_2`.`media_15` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_15)';

CREATE TABLE `turbo_feed_2`.`media_16` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_16)';

CREATE TABLE `turbo_feed_2`.`media_17` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_17)';

CREATE TABLE `turbo_feed_2`.`media_18` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_18)';

CREATE TABLE `turbo_feed_2`.`media_19` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_19)';

CREATE TABLE `turbo_feed_2`.`media_20` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_20)';

CREATE TABLE `turbo_feed_2`.`media_21` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_21)';

CREATE TABLE `turbo_feed_2`.`media_22` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_22)';

CREATE TABLE `turbo_feed_2`.`media_23` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_23)';

CREATE TABLE `turbo_feed_2`.`media_24` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_24)';

CREATE TABLE `turbo_feed_2`.`media_25` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_25)';

CREATE TABLE `turbo_feed_2`.`media_26` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_26)';

CREATE TABLE `turbo_feed_2`.`media_27` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_27)';

CREATE TABLE `turbo_feed_2`.`media_28` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_28)';

CREATE TABLE `turbo_feed_2`.`media_29` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_29)';

CREATE TABLE `turbo_feed_2`.`media_30` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_30)';

CREATE TABLE `turbo_feed_2`.`media_31` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_31)';

CREATE TABLE `turbo_feed_2`.`media_32` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_32)';

CREATE TABLE `turbo_feed_2`.`media_33` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_33)';

CREATE TABLE `turbo_feed_2`.`media_34` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_34)';

CREATE TABLE `turbo_feed_2`.`media_35` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_35)';

CREATE TABLE `turbo_feed_2`.`media_36` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_36)';

CREATE TABLE `turbo_feed_2`.`media_37` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_37)';

CREATE TABLE `turbo_feed_2`.`media_38` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_38)';

CREATE TABLE `turbo_feed_2`.`media_39` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_39)';

CREATE TABLE `turbo_feed_2`.`media_40` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_40)';

CREATE TABLE `turbo_feed_2`.`media_41` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_41)';

CREATE TABLE `turbo_feed_2`.`media_42` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_42)';

CREATE TABLE `turbo_feed_2`.`media_43` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_43)';

CREATE TABLE `turbo_feed_2`.`media_44` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_44)';

CREATE TABLE `turbo_feed_2`.`media_45` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_45)';

CREATE TABLE `turbo_feed_2`.`media_46` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_46)';

CREATE TABLE `turbo_feed_2`.`media_47` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_47)';

CREATE TABLE `turbo_feed_2`.`media_48` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_48)';

CREATE TABLE `turbo_feed_2`.`media_49` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_49)';

CREATE TABLE `turbo_feed_2`.`media_50` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_50)';

CREATE TABLE `turbo_feed_2`.`media_51` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_51)';

CREATE TABLE `turbo_feed_2`.`media_52` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_52)';

CREATE TABLE `turbo_feed_2`.`media_53` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_53)';

CREATE TABLE `turbo_feed_2`.`media_54` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_54)';

CREATE TABLE `turbo_feed_2`.`media_55` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_55)';

CREATE TABLE `turbo_feed_2`.`media_56` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_56)';

CREATE TABLE `turbo_feed_2`.`media_57` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_57)';

CREATE TABLE `turbo_feed_2`.`media_58` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_58)';

CREATE TABLE `turbo_feed_2`.`media_59` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_59)';

CREATE TABLE `turbo_feed_2`.`media_60` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_60)';

CREATE TABLE `turbo_feed_2`.`media_61` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_61)';

CREATE TABLE `turbo_feed_2`.`media_62` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_62)';

CREATE TABLE `turbo_feed_2`.`media_63` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_63)';

