-- =============================================================================
-- turbo-feed 本地 MySQL 初始化脚本（幂等、可重复执行）
-- -----------------------------------------------------------------------------
-- 作用：建 turbo_feed_1 / turbo_feed_2 两库 + 物理分片表，供 ShardingSphere 路由。
--       与 tf-gateway 的 shardingsphere-config.yaml 中 ds_0/ds_1 一一对应
--       （root/123456，127.0.0.1:3306）。
--
-- ⚠️ 物理表命名必须与 ShardingSphere autoTables + HASH_MOD 的推断一致：
--    各逻辑表分片数不同：user / account_credit / violation_record / account_penalty / report / appeal / comment = sharding-count 4；
--    media = sharding-count 128（2 库 × 64 表 = 128 物理表：media_0..media_127，偶数下标落 ds_0、奇数下标落 ds_1）。
--    user_0..user_3（_4 表）按 actualDataSources.get(i % ds数) 落到库：
--      ds_0(turbo_feed_1): user_0/_2, account_credit_0/_2, ...（_4 表） + media_0/_2/.../_126（偶数下标 64 张）
--      ds_1(turbo_feed_2): user_1/_3, account_credit_1/_3, ...（_4 表） + media_1/_3/.../_127（奇数下标 64 张）
--    若命名/分布不符，会报 Table doesn't exist 或路由错乱。请勿改成 user_0/user_1 各库两份。
--
-- 用法（在本仓库根目录执行）：
--   mysql -uroot -p123456 < deploy/mysql/init-local.sql
-- 顶部先 DROP 两库再重建，保证 ShardingSphere 元数据以最新 DDL（含 phone 列）为准，
-- 避免 CREATE TABLE IF NOT EXISTS 跳过重建导致「列不存在」类陈旧元数据问题。
--
-- ⚠️ 本脚本是 DROP 重建（保证元数据以最新 DDL 为准）。若只想让<b>存量库</b>增量对齐而不想丢数据，
--    必须自行补：① 新增表（例：0036 的 user_phone_router / 0037 的 outbox_event / 0034 的
--    violation_record_*、account_penalty_*）② 新增列（例：0033 的 last_violation_at、watch_since）。
--    漏补的表现是运行期报 TableNotFoundException / ColumnNotFoundException——
--    ShardingSphere 在启动时加载元数据，补完表/列<b>必须重启应用</b>才生效。
-- 说明：user_phone_router（手机号→UID 路由表）已启用，建在 ds_0 单表——注册去重与登录定位
--      不再广播全分片。存量库回填语句见本文件 user_phone_router 建表处注释。
-- =============================================================================

DROP DATABASE IF EXISTS turbo_feed_1;
DROP DATABASE IF EXISTS turbo_feed_2;
CREATE DATABASE turbo_feed_1 DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE turbo_feed_2 DEFAULT CHARACTER SET utf8mb4;

-- ==================== 库 1：turbo_feed_1（ds_0）===================
-- ds_0 承载编号偶数下标的物理表：user_0/user_2、media_0/media_2
CREATE TABLE `turbo_feed_1`.`user_0` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色: USER/REVIEWER/ADMIN（RBAC 权限来源，登录直接读库派生 JWT）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(物理分片 turbo_feed_1.user_0)';

CREATE TABLE `turbo_feed_1`.`user_2` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色: USER/REVIEWER/ADMIN（RBAC 权限来源，登录直接读库派生 JWT）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(物理分片 turbo_feed_1.user_2)';

-- ==================== 媒体表（media_0..media_126，分片键 user_id，HASH_MOD 128）====================
-- 2 库 × 64 表 = 128 物理表；偶数下标落 ds_0(turbo_feed_1)、奇数下标落 ds_1(turbo_feed_2)。
-- 与 shardingsphere-config-128.yaml / shardingsphere-config-local.yml 的 media 规则一一对应。
CREATE TABLE `turbo_feed_1`.`media_0` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_0)';
CREATE TABLE `turbo_feed_1`.`media_2` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_2)';
CREATE TABLE `turbo_feed_1`.`media_4` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_4)';
CREATE TABLE `turbo_feed_1`.`media_6` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_6)';
CREATE TABLE `turbo_feed_1`.`media_8` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_8)';
CREATE TABLE `turbo_feed_1`.`media_10` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_10)';
CREATE TABLE `turbo_feed_1`.`media_12` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_12)';
CREATE TABLE `turbo_feed_1`.`media_14` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_14)';
CREATE TABLE `turbo_feed_1`.`media_16` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_16)';
CREATE TABLE `turbo_feed_1`.`media_18` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_18)';
CREATE TABLE `turbo_feed_1`.`media_20` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_20)';
CREATE TABLE `turbo_feed_1`.`media_22` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_22)';
CREATE TABLE `turbo_feed_1`.`media_24` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_24)';
CREATE TABLE `turbo_feed_1`.`media_26` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_26)';
CREATE TABLE `turbo_feed_1`.`media_28` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_28)';
CREATE TABLE `turbo_feed_1`.`media_30` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_30)';
CREATE TABLE `turbo_feed_1`.`media_32` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_32)';
CREATE TABLE `turbo_feed_1`.`media_34` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_34)';
CREATE TABLE `turbo_feed_1`.`media_36` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_36)';
CREATE TABLE `turbo_feed_1`.`media_38` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_38)';
CREATE TABLE `turbo_feed_1`.`media_40` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_40)';
CREATE TABLE `turbo_feed_1`.`media_42` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_42)';
CREATE TABLE `turbo_feed_1`.`media_44` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_44)';
CREATE TABLE `turbo_feed_1`.`media_46` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_46)';
CREATE TABLE `turbo_feed_1`.`media_48` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_48)';
CREATE TABLE `turbo_feed_1`.`media_50` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_50)';
CREATE TABLE `turbo_feed_1`.`media_52` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_52)';
CREATE TABLE `turbo_feed_1`.`media_54` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_54)';
CREATE TABLE `turbo_feed_1`.`media_56` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_56)';
CREATE TABLE `turbo_feed_1`.`media_58` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_58)';
CREATE TABLE `turbo_feed_1`.`media_60` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_60)';
CREATE TABLE `turbo_feed_1`.`media_62` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_62)';
CREATE TABLE `turbo_feed_1`.`media_64` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_64)';
CREATE TABLE `turbo_feed_1`.`media_66` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_66)';
CREATE TABLE `turbo_feed_1`.`media_68` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_68)';
CREATE TABLE `turbo_feed_1`.`media_70` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_70)';
CREATE TABLE `turbo_feed_1`.`media_72` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_72)';
CREATE TABLE `turbo_feed_1`.`media_74` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_74)';
CREATE TABLE `turbo_feed_1`.`media_76` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_76)';
CREATE TABLE `turbo_feed_1`.`media_78` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_78)';
CREATE TABLE `turbo_feed_1`.`media_80` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_80)';
CREATE TABLE `turbo_feed_1`.`media_82` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_82)';
CREATE TABLE `turbo_feed_1`.`media_84` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_84)';
CREATE TABLE `turbo_feed_1`.`media_86` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_86)';
CREATE TABLE `turbo_feed_1`.`media_88` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_88)';
CREATE TABLE `turbo_feed_1`.`media_90` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_90)';
CREATE TABLE `turbo_feed_1`.`media_92` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_92)';
CREATE TABLE `turbo_feed_1`.`media_94` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_94)';
CREATE TABLE `turbo_feed_1`.`media_96` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_96)';
CREATE TABLE `turbo_feed_1`.`media_98` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_98)';
CREATE TABLE `turbo_feed_1`.`media_100` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_100)';
CREATE TABLE `turbo_feed_1`.`media_102` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_102)';
CREATE TABLE `turbo_feed_1`.`media_104` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_104)';
CREATE TABLE `turbo_feed_1`.`media_106` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_106)';
CREATE TABLE `turbo_feed_1`.`media_108` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_108)';
CREATE TABLE `turbo_feed_1`.`media_110` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_110)';
CREATE TABLE `turbo_feed_1`.`media_112` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_112)';
CREATE TABLE `turbo_feed_1`.`media_114` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_114)';
CREATE TABLE `turbo_feed_1`.`media_116` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_116)';
CREATE TABLE `turbo_feed_1`.`media_118` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_118)';
CREATE TABLE `turbo_feed_1`.`media_120` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_120)';
CREATE TABLE `turbo_feed_1`.`media_122` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_122)';
CREATE TABLE `turbo_feed_1`.`media_124` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_124)';
CREATE TABLE `turbo_feed_1`.`media_126` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_126)';

-- ==================== 分片表（抖音式审核骨架）：account_credit / report / appeal ====================
-- 三张表不再走单表，改为与 user 同策略的 HASH_MOD 分片（sharding-count=4，2 库，与 media 的 128 分片正交），
-- 物理表命名 <逻辑表>_<0..3>，偶数下标(_0/_2)落 ds_0、奇数下标(_1/_3)落 ds_1（media 已独立扩到 128）。
-- 分片键：account_credit=user_id（与 user 同片）；report/appeal=media_id（举报/申诉高频查询均按 media_id 命中单分片）。
-- 配置见 shardingsphere-config.yaml 的 autoTables（已移除 SINGLE 规则）。

-- 账号信用分级表（分片键 user_id）：高信用(L2)先发后审入大池 / 普通(L1)先发后审入小池 / 低信用·新号(L0)先审后放。
CREATE TABLE `turbo_feed_1`.`account_credit_0` (
  `user_id`           BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id), 与 user/media 同片',
  `credit_score`      INT          NOT NULL DEFAULT 100    COMMENT '信用分(0-100), 越低越严',
  `level`             TINYINT      NOT NULL DEFAULT 1      COMMENT '信用等级 0=L0(先审后放) 1=L1(小池) 2=L2(大池)',
  `strict_queue_flag` TINYINT      NOT NULL DEFAULT 0      COMMENT '0=普通 1=加严队列(近30天有下架, 所有内容先审后放)',
  `new_user_watch`   TINYINT      NOT NULL DEFAULT 0      COMMENT '1=新人观察期(先审后放), 人审通过达阈值后自动置 0',
  `new_user_approved_count` INT  NOT NULL DEFAULT 0      COMMENT '新人观察期内累计人审通过帖数(仅统计人工通过)',
  `last_violation_at` DATETIME     DEFAULT NULL          COMMENT '最近一次违规时点(P0-5, 超 strictQueueWindowDays 自动解除加严)',
  `watch_since`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '观察期起始(P0-6, 超 newUserWatchWindowDays 自动转正)',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号信用分级表(物理分片 turbo_feed_1.account_credit_0)';

CREATE TABLE `turbo_feed_1`.`account_credit_2` (
  `user_id`           BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id), 与 user/media 同片',
  `credit_score`      INT          NOT NULL DEFAULT 100    COMMENT '信用分(0-100), 越低越严',
  `level`             TINYINT      NOT NULL DEFAULT 1      COMMENT '信用等级 0=L0(先审后放) 1=L1(小池) 2=L2(大池)',
  `strict_queue_flag` TINYINT      NOT NULL DEFAULT 0      COMMENT '0=普通 1=加严队列(近30天有下架, 所有内容先审后放)',
  `new_user_watch`   TINYINT      NOT NULL DEFAULT 0      COMMENT '1=新人观察期(先审后放), 人审通过达阈值后自动置 0',
  `new_user_approved_count` INT  NOT NULL DEFAULT 0      COMMENT '新人观察期内累计人审通过帖数(仅统计人工通过)',
  `last_violation_at` DATETIME     DEFAULT NULL          COMMENT '最近一次违规时点(P0-5, 超 strictQueueWindowDays 自动解除加严)',
  `watch_since`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '观察期起始(P0-6, 超 newUserWatchWindowDays 自动转正)',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号信用分级表(物理分片 turbo_feed_1.account_credit_2)';

-- 用户举报表（分片键 media_id）：对任意已发布内容举报写本表；高危理由由服务层 fail-closed 立即下架。
CREATE TABLE `turbo_feed_1`.`report_0` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '举报记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被举报内容ID, 分片键',
  `reporter_user_id`  BIGINT       NOT NULL                COMMENT '举报人用户ID',
  `reason`            VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '举报理由(含涉政/暴恐/儿童等高危词→立即下架)',
  `category`          TINYINT      NOT NULL DEFAULT 0      COMMENT '举报类目(ViolationCategory code,0=OTHER, P1-1 同类累计)',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待处理 1=已确认违规 2=已驳回',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_reporter` (`reporter_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容举报表(物理分片 turbo_feed_1.report_0)';

CREATE TABLE `turbo_feed_1`.`report_2` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '举报记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被举报内容ID, 分片键',
  `reporter_user_id`  BIGINT       NOT NULL                COMMENT '举报人用户ID',
  `reason`            VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '举报理由(含涉政/暴恐/儿童等高危词→立即下架)',
  `category`          TINYINT      NOT NULL DEFAULT 0      COMMENT '举报类目(ViolationCategory code,0=OTHER, P1-1 同类累计)',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待处理 1=已确认违规 2=已驳回',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_reporter` (`reporter_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容举报表(物理分片 turbo_feed_1.report_2)';

-- 作者申诉表（分片键 media_id）：作者对自身被驳回/下架内容申诉写本表；管理员复核翻案/维持。
CREATE TABLE `turbo_feed_1`.`appeal_0` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申诉记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被申诉内容ID, 分片键',
  `author_user_id`    BIGINT       NOT NULL                COMMENT '申诉作者用户ID',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=申诉中 1=翻案(恢复) 2=维持(驳回)',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申诉时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_author` (`author_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容申诉表(物理分片 turbo_feed_1.appeal_0)';

CREATE TABLE `turbo_feed_1`.`appeal_2` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申诉记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被申诉内容ID, 分片键',
  `author_user_id`    BIGINT       NOT NULL                COMMENT '申诉作者用户ID',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=申诉中 1=翻案(恢复) 2=维持(驳回)',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申诉时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_author` (`author_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容申诉表(物理分片 turbo_feed_1.appeal_2)';

-- ==================== 库 2：turbo_feed_2（ds_1）===================
-- ds_1 承载编号奇数下标的物理表：user_1/user_3、media_1/media_3
CREATE TABLE `turbo_feed_2`.`user_1` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色: USER/REVIEWER/ADMIN（RBAC 权限来源，登录直接读库派生 JWT）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(物理分片 turbo_feed_2.user_1)';

CREATE TABLE `turbo_feed_2`.`user_3` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色: USER/REVIEWER/ADMIN（RBAC 权限来源，登录直接读库派生 JWT）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(物理分片 turbo_feed_2.user_3)';

-- ==================== 媒体表（media_1..media_127，分片键 user_id，HASH_MOD 128）====================
-- 2 库 × 64 表 = 128 物理表；偶数下标落 ds_0(turbo_feed_1)、奇数下标落 ds_1(turbo_feed_2)。
-- 与 shardingsphere-config-128.yaml / shardingsphere-config-local.yml 的 media 规则一一对应。
CREATE TABLE `turbo_feed_2`.`media_1` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_1)';
CREATE TABLE `turbo_feed_2`.`media_3` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_3)';
CREATE TABLE `turbo_feed_2`.`media_5` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_5)';
CREATE TABLE `turbo_feed_2`.`media_7` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_7)';
CREATE TABLE `turbo_feed_2`.`media_9` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_9)';
CREATE TABLE `turbo_feed_2`.`media_11` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_11)';
CREATE TABLE `turbo_feed_2`.`media_13` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_13)';
CREATE TABLE `turbo_feed_2`.`media_15` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_15)';
CREATE TABLE `turbo_feed_2`.`media_17` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_17)';
CREATE TABLE `turbo_feed_2`.`media_19` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_19)';
CREATE TABLE `turbo_feed_2`.`media_21` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_21)';
CREATE TABLE `turbo_feed_2`.`media_23` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_23)';
CREATE TABLE `turbo_feed_2`.`media_25` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_25)';
CREATE TABLE `turbo_feed_2`.`media_27` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_27)';
CREATE TABLE `turbo_feed_2`.`media_29` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_29)';
CREATE TABLE `turbo_feed_2`.`media_31` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_31)';
CREATE TABLE `turbo_feed_2`.`media_33` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_33)';
CREATE TABLE `turbo_feed_2`.`media_35` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_35)';
CREATE TABLE `turbo_feed_2`.`media_37` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_37)';
CREATE TABLE `turbo_feed_2`.`media_39` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_39)';
CREATE TABLE `turbo_feed_2`.`media_41` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_41)';
CREATE TABLE `turbo_feed_2`.`media_43` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_43)';
CREATE TABLE `turbo_feed_2`.`media_45` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_45)';
CREATE TABLE `turbo_feed_2`.`media_47` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_47)';
CREATE TABLE `turbo_feed_2`.`media_49` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_49)';
CREATE TABLE `turbo_feed_2`.`media_51` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_51)';
CREATE TABLE `turbo_feed_2`.`media_53` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_53)';
CREATE TABLE `turbo_feed_2`.`media_55` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_55)';
CREATE TABLE `turbo_feed_2`.`media_57` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_57)';
CREATE TABLE `turbo_feed_2`.`media_59` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_59)';
CREATE TABLE `turbo_feed_2`.`media_61` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_61)';
CREATE TABLE `turbo_feed_2`.`media_63` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_63)';
CREATE TABLE `turbo_feed_2`.`media_65` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_65)';
CREATE TABLE `turbo_feed_2`.`media_67` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_67)';
CREATE TABLE `turbo_feed_2`.`media_69` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_69)';
CREATE TABLE `turbo_feed_2`.`media_71` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_71)';
CREATE TABLE `turbo_feed_2`.`media_73` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_73)';
CREATE TABLE `turbo_feed_2`.`media_75` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_75)';
CREATE TABLE `turbo_feed_2`.`media_77` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_77)';
CREATE TABLE `turbo_feed_2`.`media_79` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_79)';
CREATE TABLE `turbo_feed_2`.`media_81` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_81)';
CREATE TABLE `turbo_feed_2`.`media_83` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_83)';
CREATE TABLE `turbo_feed_2`.`media_85` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_85)';
CREATE TABLE `turbo_feed_2`.`media_87` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_87)';
CREATE TABLE `turbo_feed_2`.`media_89` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_89)';
CREATE TABLE `turbo_feed_2`.`media_91` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_91)';
CREATE TABLE `turbo_feed_2`.`media_93` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_93)';
CREATE TABLE `turbo_feed_2`.`media_95` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_95)';
CREATE TABLE `turbo_feed_2`.`media_97` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_97)';
CREATE TABLE `turbo_feed_2`.`media_99` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_99)';
CREATE TABLE `turbo_feed_2`.`media_101` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_101)';
CREATE TABLE `turbo_feed_2`.`media_103` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_103)';
CREATE TABLE `turbo_feed_2`.`media_105` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_105)';
CREATE TABLE `turbo_feed_2`.`media_107` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_107)';
CREATE TABLE `turbo_feed_2`.`media_109` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_109)';
CREATE TABLE `turbo_feed_2`.`media_111` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_111)';
CREATE TABLE `turbo_feed_2`.`media_113` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_113)';
CREATE TABLE `turbo_feed_2`.`media_115` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_115)';
CREATE TABLE `turbo_feed_2`.`media_117` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_117)';
CREATE TABLE `turbo_feed_2`.`media_119` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_119)';
CREATE TABLE `turbo_feed_2`.`media_121` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_121)';
CREATE TABLE `turbo_feed_2`.`media_123` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_123)';
CREATE TABLE `turbo_feed_2`.`media_125` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_125)';
CREATE TABLE `turbo_feed_2`.`media_127` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖, 查询时回退按 media_id 当帖)',
  `seq`        TINYINT      NOT NULL DEFAULT 0       COMMENT '帖内图片序号(0起, 决定轮播顺序)',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `duration`   INT           NULL                    COMMENT '时长(秒); 视频为真实时长, 图文为期望驻留时长; 完播率分母',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_127)';

-- ==================== 分片表（抖音式审核骨架，ds_1 奇数下标）：account_credit / report / appeal ====================
-- 与 ds_0 区对称：奇数下标(_1/_3)落 ds_1(turbo_feed_2)，分片键与 ds_0 区完全一致。

CREATE TABLE `turbo_feed_2`.`account_credit_1` (
  `user_id`           BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id), 与 user/media 同片',
  `credit_score`      INT          NOT NULL DEFAULT 100    COMMENT '信用分(0-100), 越低越严',
  `level`             TINYINT      NOT NULL DEFAULT 1      COMMENT '信用等级 0=L0(先审后放) 1=L1(小池) 2=L2(大池)',
  `strict_queue_flag` TINYINT      NOT NULL DEFAULT 0      COMMENT '0=普通 1=加严队列(近30天有下架, 所有内容先审后放)',
  `new_user_watch`   TINYINT      NOT NULL DEFAULT 0      COMMENT '1=新人观察期(先审后放), 人审通过达阈值后自动置 0',
  `new_user_approved_count` INT  NOT NULL DEFAULT 0      COMMENT '新人观察期内累计人审通过帖数(仅统计人工通过)',
  `last_violation_at` DATETIME     DEFAULT NULL          COMMENT '最近一次违规时点(P0-5, 超 strictQueueWindowDays 自动解除加严)',
  `watch_since`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '观察期起始(P0-6, 超 newUserWatchWindowDays 自动转正)',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号信用分级表(物理分片 turbo_feed_2.account_credit_1)';

CREATE TABLE `turbo_feed_2`.`account_credit_3` (
  `user_id`           BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id), 与 user/media 同片',
  `credit_score`      INT          NOT NULL DEFAULT 100    COMMENT '信用分(0-100), 越低越严',
  `level`             TINYINT      NOT NULL DEFAULT 1      COMMENT '信用等级 0=L0(先审后放) 1=L1(小池) 2=L2(大池)',
  `strict_queue_flag` TINYINT      NOT NULL DEFAULT 0      COMMENT '0=普通 1=加严队列(近30天有下架, 所有内容先审后放)',
  `new_user_watch`   TINYINT      NOT NULL DEFAULT 0      COMMENT '1=新人观察期(先审后放), 人审通过达阈值后自动置 0',
  `new_user_approved_count` INT  NOT NULL DEFAULT 0      COMMENT '新人观察期内累计人审通过帖数(仅统计人工通过)',
  `last_violation_at` DATETIME     DEFAULT NULL          COMMENT '最近一次违规时点(P0-5, 超 strictQueueWindowDays 自动解除加严)',
  `watch_since`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '观察期起始(P0-6, 超 newUserWatchWindowDays 自动转正)',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号信用分级表(物理分片 turbo_feed_2.account_credit_3)';

-- ---------------------------------------------------------------------------
-- 处罚域（penalty 包骨架）：与 account_credit「软声誉」领域分离的两张表。
--   violation_record —— append-only 审计，升级决策的事实源（谁/何类目/何严重度/何来源/何处置）。
--   account_penalty  —— 账号级硬执行处罚态（封禁/警告），直接决定能不能写。
-- 均为分片键 user_id（与 account_credit 同键同算法 → 同片），物理表 <表>_0.._3，
-- 偶数下标(_0/_2)落 ds_0、奇数下标(_1/_3)落 ds_1。
-- ---------------------------------------------------------------------------

CREATE TABLE `turbo_feed_1`.`violation_record_0` (
  `id`               BIGINT       NOT NULL                COMMENT '违规记录ID, ShardingSphere雪花填充',
  `user_id`          BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id)',
  `category`         TINYINT      NOT NULL                COMMENT '违规类目 0其他(未分类兜底) 1色情 2政治 3暴力 4广告 5攻击 6账号安全 7刷量',
  `severity`         TINYINT      NOT NULL                COMMENT '严重度 1低 2中 3高 4严重(CRITICAL)',
  `source`           TINYINT      NOT NULL                COMMENT '来源 1机审 2举报 3人审 4申诉翻案',
  `action_taken`     TINYINT      NOT NULL DEFAULT 0      COMMENT '处置 0无 1警告 2扣分 3加严 4临时封 5永久封 6解除(撤销封禁, changelog 0035)',
  `related_media_id` VARCHAR(255) DEFAULT NULL            COMMENT '关联内容ID(内容违规时填, 账号/行为违规可空)',
  `reason`           VARCHAR(512) DEFAULT NULL            COMMENT '处置理由(人审/运营填写)',
  `operator`         VARCHAR(64)  DEFAULT NULL            COMMENT '操作人(SYSTEM/审核员ID)',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`user_id`, `id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规记录表(append-only, 物理分片 turbo_feed_1.violation_record_0)';

CREATE TABLE `turbo_feed_1`.`violation_record_2` (
  `id`               BIGINT       NOT NULL                COMMENT '违规记录ID, ShardingSphere雪花填充',
  `user_id`          BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id)',
  `category`         TINYINT      NOT NULL                COMMENT '违规类目 0其他(未分类兜底) 1色情 2政治 3暴力 4广告 5攻击 6账号安全 7刷量',
  `severity`         TINYINT      NOT NULL                COMMENT '严重度 1低 2中 3高 4严重(CRITICAL)',
  `source`           TINYINT      NOT NULL                COMMENT '来源 1机审 2举报 3人审 4申诉翻案',
  `action_taken`     TINYINT      NOT NULL DEFAULT 0      COMMENT '处置 0无 1警告 2扣分 3加严 4临时封 5永久封 6解除(撤销封禁, changelog 0035)',
  `related_media_id` VARCHAR(255) DEFAULT NULL            COMMENT '关联内容ID(内容违规时填, 账号/行为违规可空)',
  `reason`           VARCHAR(512) DEFAULT NULL            COMMENT '处置理由(人审/运营填写)',
  `operator`         VARCHAR(64)  DEFAULT NULL            COMMENT '操作人(SYSTEM/审核员ID)',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`user_id`, `id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规记录表(append-only, 物理分片 turbo_feed_1.violation_record_2)';

CREATE TABLE `turbo_feed_2`.`violation_record_1` (
  `id`               BIGINT       NOT NULL                COMMENT '违规记录ID, ShardingSphere雪花填充',
  `user_id`          BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id)',
  `category`         TINYINT      NOT NULL                COMMENT '违规类目 0其他(未分类兜底) 1色情 2政治 3暴力 4广告 5攻击 6账号安全 7刷量',
  `severity`         TINYINT      NOT NULL                COMMENT '严重度 1低 2中 3高 4严重(CRITICAL)',
  `source`           TINYINT      NOT NULL                COMMENT '来源 1机审 2举报 3人审 4申诉翻案',
  `action_taken`     TINYINT      NOT NULL DEFAULT 0      COMMENT '处置 0无 1警告 2扣分 3加严 4临时封 5永久封 6解除(撤销封禁, changelog 0035)',
  `related_media_id` VARCHAR(255) DEFAULT NULL            COMMENT '关联内容ID(内容违规时填, 账号/行为违规可空)',
  `reason`           VARCHAR(512) DEFAULT NULL            COMMENT '处置理由(人审/运营填写)',
  `operator`         VARCHAR(64)  DEFAULT NULL            COMMENT '操作人(SYSTEM/审核员ID)',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`user_id`, `id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规记录表(append-only, 物理分片 turbo_feed_2.violation_record_1)';

CREATE TABLE `turbo_feed_2`.`violation_record_3` (
  `id`               BIGINT       NOT NULL                COMMENT '违规记录ID, ShardingSphere雪花填充',
  `user_id`          BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id)',
  `category`         TINYINT      NOT NULL                COMMENT '违规类目 0其他(未分类兜底) 1色情 2政治 3暴力 4广告 5攻击 6账号安全 7刷量',
  `severity`         TINYINT      NOT NULL                COMMENT '严重度 1低 2中 3高 4严重(CRITICAL)',
  `source`           TINYINT      NOT NULL                COMMENT '来源 1机审 2举报 3人审 4申诉翻案',
  `action_taken`     TINYINT      NOT NULL DEFAULT 0      COMMENT '处置 0无 1警告 2扣分 3加严 4临时封 5永久封 6解除(撤销封禁, changelog 0035)',
  `related_media_id` VARCHAR(255) DEFAULT NULL            COMMENT '关联内容ID(内容违规时填, 账号/行为违规可空)',
  `reason`           VARCHAR(512) DEFAULT NULL            COMMENT '处置理由(人审/运营填写)',
  `operator`         VARCHAR(64)  DEFAULT NULL            COMMENT '操作人(SYSTEM/审核员ID)',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`user_id`, `id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规记录表(append-only, 物理分片 turbo_feed_2.violation_record_3)';

CREATE TABLE `turbo_feed_1`.`account_penalty_0` (
  `user_id`            BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id)',
  `status`             TINYINT      NOT NULL DEFAULT 0      COMMENT '处罚态 0正常 1警告 2临时封 3永久封',
  `ban_category`       TINYINT      DEFAULT NULL            COMMENT '触发封禁的类目(见 violation_record.category)',
  `ban_reason`         VARCHAR(512) DEFAULT NULL            COMMENT '封禁理由',
  `ban_until`          DATETIME     DEFAULT NULL            COMMENT '临时封禁到期时点(NULL=永久封禁)',
  `violation_count`    INT          NOT NULL DEFAULT 0      COMMENT '累计违规次数(升级判断输入)',
  `first_violation_at` DATETIME     DEFAULT NULL            COMMENT '首次违规时点',
  `last_violation_at`  DATETIME     DEFAULT NULL            COMMENT '最近一次违规时点',
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号处罚态表(物理分片 turbo_feed_1.account_penalty_0)';

CREATE TABLE `turbo_feed_1`.`account_penalty_2` (
  `user_id`            BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id)',
  `status`             TINYINT      NOT NULL DEFAULT 0      COMMENT '处罚态 0正常 1警告 2临时封 3永久封',
  `ban_category`       TINYINT      DEFAULT NULL            COMMENT '触发封禁的类目(见 violation_record.category)',
  `ban_reason`         VARCHAR(512) DEFAULT NULL            COMMENT '封禁理由',
  `ban_until`          DATETIME     DEFAULT NULL            COMMENT '临时封禁到期时点(NULL=永久封禁)',
  `violation_count`    INT          NOT NULL DEFAULT 0      COMMENT '累计违规次数(升级判断输入)',
  `first_violation_at` DATETIME     DEFAULT NULL            COMMENT '首次违规时点',
  `last_violation_at`  DATETIME     DEFAULT NULL            COMMENT '最近一次违规时点',
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号处罚态表(物理分片 turbo_feed_1.account_penalty_2)';

CREATE TABLE `turbo_feed_2`.`account_penalty_1` (
  `user_id`            BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id)',
  `status`             TINYINT      NOT NULL DEFAULT 0      COMMENT '处罚态 0正常 1警告 2临时封 3永久封',
  `ban_category`       TINYINT      DEFAULT NULL            COMMENT '触发封禁的类目(见 violation_record.category)',
  `ban_reason`         VARCHAR(512) DEFAULT NULL            COMMENT '封禁理由',
  `ban_until`          DATETIME     DEFAULT NULL            COMMENT '临时封禁到期时点(NULL=永久封禁)',
  `violation_count`    INT          NOT NULL DEFAULT 0      COMMENT '累计违规次数(升级判断输入)',
  `first_violation_at` DATETIME     DEFAULT NULL            COMMENT '首次违规时点',
  `last_violation_at`  DATETIME     DEFAULT NULL            COMMENT '最近一次违规时点',
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号处罚态表(物理分片 turbo_feed_2.account_penalty_1)';

CREATE TABLE `turbo_feed_2`.`account_penalty_3` (
  `user_id`            BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id)',
  `status`             TINYINT      NOT NULL DEFAULT 0      COMMENT '处罚态 0正常 1警告 2临时封 3永久封',
  `ban_category`       TINYINT      DEFAULT NULL            COMMENT '触发封禁的类目(见 violation_record.category)',
  `ban_reason`         VARCHAR(512) DEFAULT NULL            COMMENT '封禁理由',
  `ban_until`          DATETIME     DEFAULT NULL            COMMENT '临时封禁到期时点(NULL=永久封禁)',
  `violation_count`    INT          NOT NULL DEFAULT 0      COMMENT '累计违规次数(升级判断输入)',
  `first_violation_at` DATETIME     DEFAULT NULL            COMMENT '首次违规时点',
  `last_violation_at`  DATETIME     DEFAULT NULL            COMMENT '最近一次违规时点',
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号处罚态表(物理分片 turbo_feed_2.account_penalty_3)';

CREATE TABLE `turbo_feed_2`.`report_1` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '举报记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被举报内容ID, 分片键',
  `reporter_user_id`  BIGINT       NOT NULL                COMMENT '举报人用户ID',
  `reason`            VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '举报理由(含涉政/暴恐/儿童等高危词→立即下架)',
  `category`          TINYINT      NOT NULL DEFAULT 0      COMMENT '举报类目(ViolationCategory code,0=OTHER, P1-1 同类累计)',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待处理 1=已确认违规 2=已驳回',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_reporter` (`reporter_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容举报表(物理分片 turbo_feed_2.report_1)';

CREATE TABLE `turbo_feed_2`.`report_3` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '举报记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被举报内容ID, 分片键',
  `reporter_user_id`  BIGINT       NOT NULL                COMMENT '举报人用户ID',
  `reason`            VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '举报理由(含涉政/暴恐/儿童等高危词→立即下架)',
  `category`          TINYINT      NOT NULL DEFAULT 0      COMMENT '举报类目(ViolationCategory code,0=OTHER, P1-1 同类累计)',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待处理 1=已确认违规 2=已驳回',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_reporter` (`reporter_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容举报表(物理分片 turbo_feed_2.report_3)';

CREATE TABLE `turbo_feed_2`.`appeal_1` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申诉记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被申诉内容ID, 分片键',
  `author_user_id`    BIGINT       NOT NULL                COMMENT '申诉作者用户ID',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=申诉中 1=翻案(恢复) 2=维持(驳回)',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申诉时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_author` (`author_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容申诉表(物理分片 turbo_feed_2.appeal_1)';

CREATE TABLE `turbo_feed_2`.`appeal_3` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申诉记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被申诉内容ID, 分片键',
  `author_user_id`    BIGINT       NOT NULL                COMMENT '申诉作者用户ID',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=申诉中 1=翻案(恢复) 2=维持(驳回)',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申诉时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_author` (`author_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容申诉表(物理分片 turbo_feed_2.appeal_3)';

-- ==================== 评论表（comment_0..comment_127，分片键 media_id，HASH_MOD 128 节点） ====================
-- 设计：comment 独立于 media（独立包、独立读写路径），按 media_id 分片保证
--       「读某条内容下所有评论」单分片命中，与 media(user_id) 分片键正交——不依赖
--       content 所在库即可定位评论。楼中楼用 root_id(顶层=commentId) + parent_id(直接父)
--       双指针：一级列表 root_id=commentId，二级回复按 root_id 聚合按 created_at 排。
-- 扩容（④，2026-10-13）：分片数 4 -> 128，物理表由 SS autoTables 依 HASH_MOD(128) 推断
--       全局连续编号 comment_0..comment_127（偶数下标落 ds_0=turbo_feed_1、奇数 ds_1=turbo_feed_2）。
--       存量数据仅灾备（Redis 为权威），采用清空重建（A 方案），无历史灾备数据需保留。
-- 配置见 shardingsphere-config(-128).yaml 的 comment autoTable（shardingAlgorithmName: hashMod128）。
CREATE TABLE `turbo_feed_1`.`comment_0` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_0)';

CREATE TABLE `turbo_feed_2`.`comment_1` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_1)';

CREATE TABLE `turbo_feed_1`.`comment_2` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_2)';

CREATE TABLE `turbo_feed_2`.`comment_3` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_3)';

CREATE TABLE `turbo_feed_1`.`comment_4` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_4)';

CREATE TABLE `turbo_feed_2`.`comment_5` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_5)';

CREATE TABLE `turbo_feed_1`.`comment_6` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_6)';

CREATE TABLE `turbo_feed_2`.`comment_7` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_7)';

CREATE TABLE `turbo_feed_1`.`comment_8` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_8)';

CREATE TABLE `turbo_feed_2`.`comment_9` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_9)';

CREATE TABLE `turbo_feed_1`.`comment_10` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_10)';

CREATE TABLE `turbo_feed_2`.`comment_11` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_11)';

CREATE TABLE `turbo_feed_1`.`comment_12` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_12)';

CREATE TABLE `turbo_feed_2`.`comment_13` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_13)';

CREATE TABLE `turbo_feed_1`.`comment_14` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_14)';

CREATE TABLE `turbo_feed_2`.`comment_15` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_15)';

CREATE TABLE `turbo_feed_1`.`comment_16` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_16)';

CREATE TABLE `turbo_feed_2`.`comment_17` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_17)';

CREATE TABLE `turbo_feed_1`.`comment_18` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_18)';

CREATE TABLE `turbo_feed_2`.`comment_19` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_19)';

CREATE TABLE `turbo_feed_1`.`comment_20` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_20)';

CREATE TABLE `turbo_feed_2`.`comment_21` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_21)';

CREATE TABLE `turbo_feed_1`.`comment_22` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_22)';

CREATE TABLE `turbo_feed_2`.`comment_23` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_23)';

CREATE TABLE `turbo_feed_1`.`comment_24` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_24)';

CREATE TABLE `turbo_feed_2`.`comment_25` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_25)';

CREATE TABLE `turbo_feed_1`.`comment_26` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_26)';

CREATE TABLE `turbo_feed_2`.`comment_27` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_27)';

CREATE TABLE `turbo_feed_1`.`comment_28` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_28)';

CREATE TABLE `turbo_feed_2`.`comment_29` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_29)';

CREATE TABLE `turbo_feed_1`.`comment_30` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_30)';

CREATE TABLE `turbo_feed_2`.`comment_31` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_31)';

CREATE TABLE `turbo_feed_1`.`comment_32` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_32)';

CREATE TABLE `turbo_feed_2`.`comment_33` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_33)';

CREATE TABLE `turbo_feed_1`.`comment_34` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_34)';

CREATE TABLE `turbo_feed_2`.`comment_35` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_35)';

CREATE TABLE `turbo_feed_1`.`comment_36` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_36)';

CREATE TABLE `turbo_feed_2`.`comment_37` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_37)';

CREATE TABLE `turbo_feed_1`.`comment_38` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_38)';

CREATE TABLE `turbo_feed_2`.`comment_39` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_39)';

CREATE TABLE `turbo_feed_1`.`comment_40` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_40)';

CREATE TABLE `turbo_feed_2`.`comment_41` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_41)';

CREATE TABLE `turbo_feed_1`.`comment_42` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_42)';

CREATE TABLE `turbo_feed_2`.`comment_43` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_43)';

CREATE TABLE `turbo_feed_1`.`comment_44` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_44)';

CREATE TABLE `turbo_feed_2`.`comment_45` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_45)';

CREATE TABLE `turbo_feed_1`.`comment_46` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_46)';

CREATE TABLE `turbo_feed_2`.`comment_47` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_47)';

CREATE TABLE `turbo_feed_1`.`comment_48` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_48)';

CREATE TABLE `turbo_feed_2`.`comment_49` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_49)';

CREATE TABLE `turbo_feed_1`.`comment_50` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_50)';

CREATE TABLE `turbo_feed_2`.`comment_51` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_51)';

CREATE TABLE `turbo_feed_1`.`comment_52` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_52)';

CREATE TABLE `turbo_feed_2`.`comment_53` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_53)';

CREATE TABLE `turbo_feed_1`.`comment_54` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_54)';

CREATE TABLE `turbo_feed_2`.`comment_55` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_55)';

CREATE TABLE `turbo_feed_1`.`comment_56` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_56)';

CREATE TABLE `turbo_feed_2`.`comment_57` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_57)';

CREATE TABLE `turbo_feed_1`.`comment_58` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_58)';

CREATE TABLE `turbo_feed_2`.`comment_59` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_59)';

CREATE TABLE `turbo_feed_1`.`comment_60` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_60)';

CREATE TABLE `turbo_feed_2`.`comment_61` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_61)';

CREATE TABLE `turbo_feed_1`.`comment_62` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_62)';

CREATE TABLE `turbo_feed_2`.`comment_63` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_63)';

CREATE TABLE `turbo_feed_1`.`comment_64` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_64)';

CREATE TABLE `turbo_feed_2`.`comment_65` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_65)';

CREATE TABLE `turbo_feed_1`.`comment_66` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_66)';

CREATE TABLE `turbo_feed_2`.`comment_67` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_67)';

CREATE TABLE `turbo_feed_1`.`comment_68` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_68)';

CREATE TABLE `turbo_feed_2`.`comment_69` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_69)';

CREATE TABLE `turbo_feed_1`.`comment_70` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_70)';

CREATE TABLE `turbo_feed_2`.`comment_71` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_71)';

CREATE TABLE `turbo_feed_1`.`comment_72` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_72)';

CREATE TABLE `turbo_feed_2`.`comment_73` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_73)';

CREATE TABLE `turbo_feed_1`.`comment_74` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_74)';

CREATE TABLE `turbo_feed_2`.`comment_75` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_75)';

CREATE TABLE `turbo_feed_1`.`comment_76` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_76)';

CREATE TABLE `turbo_feed_2`.`comment_77` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_77)';

CREATE TABLE `turbo_feed_1`.`comment_78` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_78)';

CREATE TABLE `turbo_feed_2`.`comment_79` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_79)';

CREATE TABLE `turbo_feed_1`.`comment_80` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_80)';

CREATE TABLE `turbo_feed_2`.`comment_81` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_81)';

CREATE TABLE `turbo_feed_1`.`comment_82` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_82)';

CREATE TABLE `turbo_feed_2`.`comment_83` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_83)';

CREATE TABLE `turbo_feed_1`.`comment_84` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_84)';

CREATE TABLE `turbo_feed_2`.`comment_85` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_85)';

CREATE TABLE `turbo_feed_1`.`comment_86` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_86)';

CREATE TABLE `turbo_feed_2`.`comment_87` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_87)';

CREATE TABLE `turbo_feed_1`.`comment_88` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_88)';

CREATE TABLE `turbo_feed_2`.`comment_89` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_89)';

CREATE TABLE `turbo_feed_1`.`comment_90` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_90)';

CREATE TABLE `turbo_feed_2`.`comment_91` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_91)';

CREATE TABLE `turbo_feed_1`.`comment_92` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_92)';

CREATE TABLE `turbo_feed_2`.`comment_93` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_93)';

CREATE TABLE `turbo_feed_1`.`comment_94` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_94)';

CREATE TABLE `turbo_feed_2`.`comment_95` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_95)';

CREATE TABLE `turbo_feed_1`.`comment_96` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_96)';

CREATE TABLE `turbo_feed_2`.`comment_97` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_97)';

CREATE TABLE `turbo_feed_1`.`comment_98` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_98)';

CREATE TABLE `turbo_feed_2`.`comment_99` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_99)';

CREATE TABLE `turbo_feed_1`.`comment_100` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_100)';

CREATE TABLE `turbo_feed_2`.`comment_101` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_101)';

CREATE TABLE `turbo_feed_1`.`comment_102` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_102)';

CREATE TABLE `turbo_feed_2`.`comment_103` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_103)';

CREATE TABLE `turbo_feed_1`.`comment_104` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_104)';

CREATE TABLE `turbo_feed_2`.`comment_105` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_105)';

CREATE TABLE `turbo_feed_1`.`comment_106` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_106)';

CREATE TABLE `turbo_feed_2`.`comment_107` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_107)';

CREATE TABLE `turbo_feed_1`.`comment_108` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_108)';

CREATE TABLE `turbo_feed_2`.`comment_109` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_109)';

CREATE TABLE `turbo_feed_1`.`comment_110` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_110)';

CREATE TABLE `turbo_feed_2`.`comment_111` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_111)';

CREATE TABLE `turbo_feed_1`.`comment_112` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_112)';

CREATE TABLE `turbo_feed_2`.`comment_113` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_113)';

CREATE TABLE `turbo_feed_1`.`comment_114` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_114)';

CREATE TABLE `turbo_feed_2`.`comment_115` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_115)';

CREATE TABLE `turbo_feed_1`.`comment_116` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_116)';

CREATE TABLE `turbo_feed_2`.`comment_117` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_117)';

CREATE TABLE `turbo_feed_1`.`comment_118` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_118)';

CREATE TABLE `turbo_feed_2`.`comment_119` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_119)';

CREATE TABLE `turbo_feed_1`.`comment_120` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_120)';

CREATE TABLE `turbo_feed_2`.`comment_121` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_121)';

CREATE TABLE `turbo_feed_1`.`comment_122` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_122)';

CREATE TABLE `turbo_feed_2`.`comment_123` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_123)';

CREATE TABLE `turbo_feed_1`.`comment_124` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_124)';

CREATE TABLE `turbo_feed_2`.`comment_125` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_125)';

CREATE TABLE `turbo_feed_1`.`comment_126` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_1.comment_126)';

CREATE TABLE `turbo_feed_2`.`comment_127` (
  `comment_id` BIGINT       NOT NULL                COMMENT '雪花ID',
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '被评论内容ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '评论者用户ID',
  `root_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '根评论ID, 0=顶层',
  `parent_id`  BIGINT       NOT NULL DEFAULT 0      COMMENT '直接父评论ID, 0=顶层',
  `content`    VARCHAR(1024) NOT NULL               COMMENT '评论内容(经过敏感词过滤)',
  `status`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED 3=DELETED',
  `like_count` INT          NOT NULL DEFAULT 0      COMMENT '点赞数(冗余, 异步累加)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`, `comment_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment(物理分片 turbo_feed_2.comment_127)';

-- ==================== 视频点赞表（media_like_0..media_like_127，分片键 post_id，HASH_MOD 128 节点） ====================
-- 设计：media_like 是点赞的持久化兜底；点赞的「去重权威 + 已赞态 + 计数」实时源是
--       Redis Set tf:like:users:{postId}（SADD 原子去重、SCARD 计数、SISMEMBER 已赞态）。
--       本表仅在赞/取消时同步 INSERT/DELETE，供 Redis 重启后据表重建去重集合（运维脚本）。
--       分片键 post_id（= 时间线幂等键 timelineKey，与公域实时分 tf:post:stat:{postId}、去重集合同身份），
--       与 comment(media_id) 正交；保证「读某条内容下所有点赞」单分片命中。
-- 扩容（④，2026-10-13）：分片数 4 -> 128，物理表全局连续编号 media_like_0..media_like_127
--       （偶数下标落 ds_0=turbo_feed_1、奇数 ds_1=turbo_feed_2）。存量采用清空重建（A 方案）。
-- 配置见 shardingsphere-config(-128).yaml 的 media_like autoTable（shardingAlgorithmName: hashMod128）。
CREATE TABLE `turbo_feed_1`.`media_like_0` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_0)';

CREATE TABLE `turbo_feed_2`.`media_like_1` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_1)';

CREATE TABLE `turbo_feed_1`.`media_like_2` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_2)';

CREATE TABLE `turbo_feed_2`.`media_like_3` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_3)';

CREATE TABLE `turbo_feed_1`.`media_like_4` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_4)';

CREATE TABLE `turbo_feed_2`.`media_like_5` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_5)';

CREATE TABLE `turbo_feed_1`.`media_like_6` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_6)';

CREATE TABLE `turbo_feed_2`.`media_like_7` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_7)';

CREATE TABLE `turbo_feed_1`.`media_like_8` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_8)';

CREATE TABLE `turbo_feed_2`.`media_like_9` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_9)';

CREATE TABLE `turbo_feed_1`.`media_like_10` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_10)';

CREATE TABLE `turbo_feed_2`.`media_like_11` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_11)';

CREATE TABLE `turbo_feed_1`.`media_like_12` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_12)';

CREATE TABLE `turbo_feed_2`.`media_like_13` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_13)';

CREATE TABLE `turbo_feed_1`.`media_like_14` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_14)';

CREATE TABLE `turbo_feed_2`.`media_like_15` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_15)';

CREATE TABLE `turbo_feed_1`.`media_like_16` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_16)';

CREATE TABLE `turbo_feed_2`.`media_like_17` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_17)';

CREATE TABLE `turbo_feed_1`.`media_like_18` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_18)';

CREATE TABLE `turbo_feed_2`.`media_like_19` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_19)';

CREATE TABLE `turbo_feed_1`.`media_like_20` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_20)';

CREATE TABLE `turbo_feed_2`.`media_like_21` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_21)';

CREATE TABLE `turbo_feed_1`.`media_like_22` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_22)';

CREATE TABLE `turbo_feed_2`.`media_like_23` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_23)';

CREATE TABLE `turbo_feed_1`.`media_like_24` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_24)';

CREATE TABLE `turbo_feed_2`.`media_like_25` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_25)';

CREATE TABLE `turbo_feed_1`.`media_like_26` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_26)';

CREATE TABLE `turbo_feed_2`.`media_like_27` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_27)';

CREATE TABLE `turbo_feed_1`.`media_like_28` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_28)';

CREATE TABLE `turbo_feed_2`.`media_like_29` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_29)';

CREATE TABLE `turbo_feed_1`.`media_like_30` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_30)';

CREATE TABLE `turbo_feed_2`.`media_like_31` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_31)';

CREATE TABLE `turbo_feed_1`.`media_like_32` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_32)';

CREATE TABLE `turbo_feed_2`.`media_like_33` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_33)';

CREATE TABLE `turbo_feed_1`.`media_like_34` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_34)';

CREATE TABLE `turbo_feed_2`.`media_like_35` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_35)';

CREATE TABLE `turbo_feed_1`.`media_like_36` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_36)';

CREATE TABLE `turbo_feed_2`.`media_like_37` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_37)';

CREATE TABLE `turbo_feed_1`.`media_like_38` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_38)';

CREATE TABLE `turbo_feed_2`.`media_like_39` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_39)';

CREATE TABLE `turbo_feed_1`.`media_like_40` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_40)';

CREATE TABLE `turbo_feed_2`.`media_like_41` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_41)';

CREATE TABLE `turbo_feed_1`.`media_like_42` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_42)';

CREATE TABLE `turbo_feed_2`.`media_like_43` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_43)';

CREATE TABLE `turbo_feed_1`.`media_like_44` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_44)';

CREATE TABLE `turbo_feed_2`.`media_like_45` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_45)';

CREATE TABLE `turbo_feed_1`.`media_like_46` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_46)';

CREATE TABLE `turbo_feed_2`.`media_like_47` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_47)';

CREATE TABLE `turbo_feed_1`.`media_like_48` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_48)';

CREATE TABLE `turbo_feed_2`.`media_like_49` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_49)';

CREATE TABLE `turbo_feed_1`.`media_like_50` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_50)';

CREATE TABLE `turbo_feed_2`.`media_like_51` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_51)';

CREATE TABLE `turbo_feed_1`.`media_like_52` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_52)';

CREATE TABLE `turbo_feed_2`.`media_like_53` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_53)';

CREATE TABLE `turbo_feed_1`.`media_like_54` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_54)';

CREATE TABLE `turbo_feed_2`.`media_like_55` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_55)';

CREATE TABLE `turbo_feed_1`.`media_like_56` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_56)';

CREATE TABLE `turbo_feed_2`.`media_like_57` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_57)';

CREATE TABLE `turbo_feed_1`.`media_like_58` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_58)';

CREATE TABLE `turbo_feed_2`.`media_like_59` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_59)';

CREATE TABLE `turbo_feed_1`.`media_like_60` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_60)';

CREATE TABLE `turbo_feed_2`.`media_like_61` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_61)';

CREATE TABLE `turbo_feed_1`.`media_like_62` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_62)';

CREATE TABLE `turbo_feed_2`.`media_like_63` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_63)';

CREATE TABLE `turbo_feed_1`.`media_like_64` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_64)';

CREATE TABLE `turbo_feed_2`.`media_like_65` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_65)';

CREATE TABLE `turbo_feed_1`.`media_like_66` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_66)';

CREATE TABLE `turbo_feed_2`.`media_like_67` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_67)';

CREATE TABLE `turbo_feed_1`.`media_like_68` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_68)';

CREATE TABLE `turbo_feed_2`.`media_like_69` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_69)';

CREATE TABLE `turbo_feed_1`.`media_like_70` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_70)';

CREATE TABLE `turbo_feed_2`.`media_like_71` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_71)';

CREATE TABLE `turbo_feed_1`.`media_like_72` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_72)';

CREATE TABLE `turbo_feed_2`.`media_like_73` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_73)';

CREATE TABLE `turbo_feed_1`.`media_like_74` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_74)';

CREATE TABLE `turbo_feed_2`.`media_like_75` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_75)';

CREATE TABLE `turbo_feed_1`.`media_like_76` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_76)';

CREATE TABLE `turbo_feed_2`.`media_like_77` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_77)';

CREATE TABLE `turbo_feed_1`.`media_like_78` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_78)';

CREATE TABLE `turbo_feed_2`.`media_like_79` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_79)';

CREATE TABLE `turbo_feed_1`.`media_like_80` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_80)';

CREATE TABLE `turbo_feed_2`.`media_like_81` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_81)';

CREATE TABLE `turbo_feed_1`.`media_like_82` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_82)';

CREATE TABLE `turbo_feed_2`.`media_like_83` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_83)';

CREATE TABLE `turbo_feed_1`.`media_like_84` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_84)';

CREATE TABLE `turbo_feed_2`.`media_like_85` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_85)';

CREATE TABLE `turbo_feed_1`.`media_like_86` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_86)';

CREATE TABLE `turbo_feed_2`.`media_like_87` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_87)';

CREATE TABLE `turbo_feed_1`.`media_like_88` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_88)';

CREATE TABLE `turbo_feed_2`.`media_like_89` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_89)';

CREATE TABLE `turbo_feed_1`.`media_like_90` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_90)';

CREATE TABLE `turbo_feed_2`.`media_like_91` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_91)';

CREATE TABLE `turbo_feed_1`.`media_like_92` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_92)';

CREATE TABLE `turbo_feed_2`.`media_like_93` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_93)';

CREATE TABLE `turbo_feed_1`.`media_like_94` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_94)';

CREATE TABLE `turbo_feed_2`.`media_like_95` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_95)';

CREATE TABLE `turbo_feed_1`.`media_like_96` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_96)';

CREATE TABLE `turbo_feed_2`.`media_like_97` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_97)';

CREATE TABLE `turbo_feed_1`.`media_like_98` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_98)';

CREATE TABLE `turbo_feed_2`.`media_like_99` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_99)';

CREATE TABLE `turbo_feed_1`.`media_like_100` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_100)';

CREATE TABLE `turbo_feed_2`.`media_like_101` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_101)';

CREATE TABLE `turbo_feed_1`.`media_like_102` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_102)';

CREATE TABLE `turbo_feed_2`.`media_like_103` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_103)';

CREATE TABLE `turbo_feed_1`.`media_like_104` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_104)';

CREATE TABLE `turbo_feed_2`.`media_like_105` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_105)';

CREATE TABLE `turbo_feed_1`.`media_like_106` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_106)';

CREATE TABLE `turbo_feed_2`.`media_like_107` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_107)';

CREATE TABLE `turbo_feed_1`.`media_like_108` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_108)';

CREATE TABLE `turbo_feed_2`.`media_like_109` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_109)';

CREATE TABLE `turbo_feed_1`.`media_like_110` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_110)';

CREATE TABLE `turbo_feed_2`.`media_like_111` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_111)';

CREATE TABLE `turbo_feed_1`.`media_like_112` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_112)';

CREATE TABLE `turbo_feed_2`.`media_like_113` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_113)';

CREATE TABLE `turbo_feed_1`.`media_like_114` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_114)';

CREATE TABLE `turbo_feed_2`.`media_like_115` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_115)';

CREATE TABLE `turbo_feed_1`.`media_like_116` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_116)';

CREATE TABLE `turbo_feed_2`.`media_like_117` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_117)';

CREATE TABLE `turbo_feed_1`.`media_like_118` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_118)';

CREATE TABLE `turbo_feed_2`.`media_like_119` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_119)';

CREATE TABLE `turbo_feed_1`.`media_like_120` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_120)';

CREATE TABLE `turbo_feed_2`.`media_like_121` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_121)';

CREATE TABLE `turbo_feed_1`.`media_like_122` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_122)';

CREATE TABLE `turbo_feed_2`.`media_like_123` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_123)';

CREATE TABLE `turbo_feed_1`.`media_like_124` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_124)';

CREATE TABLE `turbo_feed_2`.`media_like_125` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_125)';

CREATE TABLE `turbo_feed_1`.`media_like_126` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_1.media_like_126)';

CREATE TABLE `turbo_feed_2`.`media_like_127` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被赞内容ID(=时间线幂等键), 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '点赞者用户ID',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_user` (`post_id`, `user_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='media_like(物理分片 turbo_feed_2.media_like_127)';

-- ==================== 敏感词库（sensitive_word，单表放 ds_0） ====================
-- ⚠️ 部署：单表必须由 shardingsphere-config.yaml 的 `!SINGLE` 规则显式登记
--    （tables: [ds_0.sensitive_word]）。旧注释「未配置规则的表走默认 ds_0」在
--    ShardingSphere 5.5.3 不成立（实测：缺 !SINGLE 时查询抛 TableNotFoundException，
--    导致词库加载失败、Trie 保持空、过滤静默失效）。
-- 词库小（KB~MB 级），单表足够；写少读多（启动 + 30s 定时 + 手动触发全量加载到 AC 自动机）。
-- 变更检测用「(COUNT, MAX(updated_at))」指纹（不是自增 revision 对比）：INSERT 改变 count、
-- UPDATE 改变 max、DELETE 改变 count，二元组任一变化即触发全量重建。revision 保留作运营审计。
-- category 用于分级处置（POLITICS/PORN/VIOLENCE/AD/...），决策层按分类查动作表
-- （turbofeed.content-security.category-actions），如「广告类只降权不拦截」。
CREATE TABLE `turbo_feed_1`.`sensitive_word` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `word`       VARCHAR(64)  NOT NULL                COMMENT '敏感词',
  `category`   VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT' COMMENT '分类: POLITICS/PORN/VIOLENCE/AD/DEFAULT',
  `enabled`    TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '0=禁用 1=启用',
  `revision`   BIGINT       NOT NULL DEFAULT 1      COMMENT '修订号, 每次写操作 +1 用于变更检测',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`),
  KEY `idx_revision` (`revision`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词库(单表, AC 自动机加载源)';

-- ==================== 误杀豁免白名单（sensitive_whitelist，单表放 ds_0） ====================
-- 任何词库都必然误杀（「黄色」「水乳交融」在某个语境下完全正常）。没有豁免出口时，
-- 运营只能二选一：删词（漏放）或留着（误杀）。白名单把「是否敏感」从「词的属性」
-- 改成「词 × 场景 × 主体」的属性，让误杀与漏放可以分别调。
-- 与 sensitive_word 同为「小表 + 读多写少 + 必须秒级生效」，共用同一套热更新模型
-- （启动加载 + 30s 指纹检测 + admin 写操作立即刷新）。
-- ⚠️ 排序规则刻意用 utf8mb4_bin（大小写/重音敏感）：白名单是「精确豁免」语义，
--    必须与 AC 的逐字符精确匹配一致；而 sensitive_word.uk_word 因库级
--    utf8mb4_0900_ai_ci 是大小写不敏感的（加 'Abc' 后再加 'abc' 不新增行、
--    文本里的 'abc' 却拦不到——实测踩过），沿用那套会让白名单意外豁免 'ABC'。
-- scene：* = 全部场景，或 NICKNAME / CAPTION / COMMENT。
-- scope：GLOBAL = 对所有人生效；USER = 仅 owner_id 本人（个案申诉，不放开全站）。
-- 部署：同样必须在 shardingsphere-config.yaml 的 `!SINGLE` 里登记 ds_0.sensitive_whitelist。
CREATE TABLE `turbo_feed_1`.`sensitive_whitelist` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `word`       VARCHAR(64)  NOT NULL                COMMENT '被豁免的词（与词库存储形态一致）',
  `scene`      VARCHAR(16)  NOT NULL DEFAULT '*'    COMMENT '生效场景: * / NICKNAME / CAPTION / COMMENT',
  `scope`      VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT '生效范围: GLOBAL / USER',
  `owner_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT 'scope=USER 时的用户 ID；GLOBAL 恒为 0',
  `reason`     VARCHAR(255)          DEFAULT NULL   COMMENT '豁免原因（运营留痕）',
  `enabled`    TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '0=禁用 1=启用',
  `revision`   BIGINT       NOT NULL DEFAULT 1      COMMENT '修订号, 每次写操作 +1',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word_scene_scope` (`word`, `scene`, `scope`, `owner_id`),
  KEY `idx_word` (`word`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='误杀豁免白名单(单表, 内存索引加载源)';

-- ==================== 手机号路由表 user_phone_router（单表，ds_0） ====================
-- 存在的唯一理由：消除「按手机号查 user」的跨分片广播。
--   user 分片键是 id，而注册去重 / 登录定位的条件是 phone —— 不带分片键，ShardingSphere
--   只能广播到全部物理表再合并。改造后：先查本表拿 uid（单表 PK 点查），再按 id 精准命中
--   user 的单个分片，两次查询都与分片数无关，为扩容（4 → 128）扫清障碍。
--
-- 为什么不分片：
--   · 注册/登录 QPS 比上传、Feed 读取低几个数量级，单表 PK 点查远未到瓶颈；
--   · 唯一性由一处 PRIMARY KEY(phone) 保证（user 表 uk_phone 只在本片内唯一，拦不住跨片重复）；
--   · 存量回填 / 人工订正只需一条同实例跨库 INSERT ... SELECT，运维成本最低。
-- 真到瓶颈时：改按 phone 挂 autoTables + HASH_MOD（该算法走 Object.hashCode()，字符串分片键可用），
-- 或迁到 Redis / 分布式 KV。
--
-- 部署：必须在 shardingsphere-config.yaml 的 `!SINGLE` 里登记 ds_0.user_phone_router，
--       否则 5.5.3 会抛 TableNotFoundException（未纳规则的表不会自动进逻辑元数据）。
--
-- 存量库回填（本文件是 DROP 重建，不需要；线上已有数据时执行一次，可重复执行）：
--   INSERT IGNORE INTO turbo_feed_1.user_phone_router (phone, user_id) SELECT phone, id FROM turbo_feed_1.user_0;
--   INSERT IGNORE INTO turbo_feed_1.user_phone_router (phone, user_id) SELECT phone, id FROM turbo_feed_1.user_2;
--   INSERT IGNORE INTO turbo_feed_1.user_phone_router (phone, user_id) SELECT phone, id FROM turbo_feed_2.user_1;
--   INSERT IGNORE INTO turbo_feed_1.user_phone_router (phone, user_id) SELECT phone, id FROM turbo_feed_2.user_3;
--   （不回填也不会出错：路由未命中时代码回退广播查询并惰性回填，只是暂时退回旧的广播成本。）
CREATE TABLE `turbo_feed_1`.`user_phone_router` (
  `phone`      VARCHAR(20) NOT NULL                COMMENT '登录手机号, 全局唯一(本表是唯一真正 enforce 该约束的地方)',
  `user_id`    BIGINT      NOT NULL                COMMENT '用户ID(user表分片键), 命中后按 id 精准查 user 单分片',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
  PRIMARY KEY (`phone`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机号→UID 路由表(单表, 消灭跨分片广播查重)';

-- ==================== 事务发件箱 outbox_event（单表，ds_0） ====================
-- P0-3：把「DB 事务」与「投递消息」的分布式原子问题，降维成「本地事务原子 + 至少一次投递」。
--   事件行与业务数据写在同一个本地事务里（同生共死），提交后由 OutboxRelay 轮询投递，
--   投失败留着下次再投；因此「消息丢失」变成「消息可能重复」，由消费端幂等消化。
--
-- 为什么单表：它就是一张队列表，按 id 顺序消费即可，没有按用户查询的诉求；
--            单表让「待投递行数」「DEAD 行数」这类对账查询一条 SQL 就能看到全貌。
-- 部署：必须在 shardingsphere-config.yaml 的 `!SINGLE` 里登记 ds_0.outbox_event。
-- 运维：DEAD 行由 OutboxDeadLetterSweeper 周期重投（带冷却、封顶 max-dead-redeliveries），达上限后保留供人工对账，确认无需补偿后自行归档删除。
CREATE TABLE `turbo_feed_1`.`outbox_event` (
  `id`              BIGINT       NOT NULL                COMMENT '事件ID(雪花), 投递顺序键',
  `event_type`      VARCHAR(64)  NOT NULL                COMMENT 'TIMELINE_APPEND / TIMELINE_REMOVE',
  `aggregate_id`    VARCHAR(255) NOT NULL                COMMENT '业务聚合标识(mediaId/postId), 对账与人工补偿定位用',
  `user_id`         BIGINT       NULL                    COMMENT '关联账号(便于按人排查; 非分片键)',
  `payload`         JSON         NOT NULL                COMMENT '事件体(投递所需全部信息, 回查库即可重放)',
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/FAILED/DEAD',
  `attempt_count`   INT          NOT NULL DEFAULT 0      COMMENT '已尝试次数(领取时 +1)',
  `max_attempts`    INT          NOT NULL DEFAULT 5      COMMENT '重试上限, 达上限置 DEAD 等人工',
  `dead_count`      INT          NOT NULL DEFAULT 0      COMMENT '死信重投次数(达上限后永久终态, 由 OutboxDeadLetterSweeper 重投)',
  `last_error`      VARCHAR(512)          DEFAULT NULL   COMMENT '最近一次失败原因(截断)',
  `next_attempt_at` DATETIME     NOT NULL                COMMENT '下次可被领取的时点(退避)',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_dispatch` (`status`, `next_attempt_at`),
  KEY `idx_reap` (`status`, `updated_at`),
  KEY `idx_aggregate` (`aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务发件箱(单表, 杜绝"事务已提交但消息丢失")';

-- ==================== 复审任务队列 review_task（单表，ds_0） ====================
-- 抖音式「举报累计 → 人工复核」的承接结构（changelog 0065）：同一内容的待处理举报数达阈值，
-- MediaReviewService#report 自动建一条 PENDING 复审任务，归 REVIEWER 二次研判。
-- 为什么单表：它是管理员视角的聚合队列，按 status 扫描全貌比按 media 点查更重要
--   （仿 outbox_event 的 SINGLE 取舍，分片后要广播合并才能看全队列，得不偿失）。
-- 部署：必须在 shardingsphere-config*.yaml 的 `!SINGLE` 里登记 ds_0.review_task。
CREATE TABLE `turbo_feed_1`.`review_task` (
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

-- ==================== 演示账号种子数据（DB 重建后可直接登录） ====================
-- 说明：网关登录现走 user 分片表 + BCrypt 校验；旧 application.yml 中 auth.demo-users
--      配置已废弃。重新执行本脚本后可用以下账号登录：
--   13800138000 / 123456  → role=ADMIN（系统管理员，进 admin.html，角色来自 user 表 role 列）
--   13700137000 / 123456  → role=REVIEWER（审核员，进 review.html，角色来自 user 表 role 列）
--   13900139000 / 123456  → role=USER（普通用户，进 user.html，角色来自 user 表 role 列）
-- 密码均为 "123456" 的 BCrypt(cost=10) 哈希，生产环境务必移除这些种子。

-- ⚠️⚠️ 分片落位必须用 HASH_MOD 的算法算，不能用 id % 4 猜！
--   HASH_MOD = Math.abs(分片键.hashCode()) % sharding-count；Long.hashCode() = (int)(v ^ (v>>>32))。
--   两者结果完全不同（例：1000000000000000000 → id%4=0，但 hashCode=-1434143053 → abs%4=1 → user_1）。
--   本段此前按 id%4 写死了落位，导致三个演示账号被插进了「非路由目标」的物理表：
--   登录按 phone 广播时能扫到（因为广播扫全部物理表），一旦改成「按 id 精准路由」就永远查不到
--   ——这是 changelog 0036 端到端验证时实测出来的，已在下表中按 HASH_MOD 真值修正。
--   以后加种子数据，落位一律先算 abs(hashCode)%4：0/2→ds_0(turbo_feed_1)，1/3→ds_1(turbo_feed_2)。
--
-- 管理员账号：abs(hashCode(1000000000000000000))%4 = 1 → user_1 → ds_1(turbo_feed_2)
-- 密码 "123456" 的 BCrypt(cost=10) 哈希，必须使用 $2a$ 前缀（Spring BCryptPasswordEncoder/jBCrypt 仅原生接受 $2a$，
-- $2y$/$2b$ 会在 hashpw 抛 Invalid salt revision）。本机用 htpasswd -B 生成的是 $2y$，改前缀为 $2a$ 即可（纯 ASCII 短口令等价）。
INSERT INTO `turbo_feed_2`.`user_1` (`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `status`, `role`, `created_at`, `updated_at`)
VALUES (1000000000000000000, '13800138000', '$2a$10$VTw0mKD3rQd2BnXAtOyhjunNcufaid1bSfdyDTk23X9HiRpMghqHi', 'DemoAdmin', '', 1, 'ADMIN', NOW(), NOW());

-- 审核员账号：abs(hashCode(1000000000000000002))%4 = 3 → user_3 → ds_1(turbo_feed_2)
-- 角色直接写在 user 表 role 列（真 RBAC），登录时 AuthService 读库派生 JWT，不在配置里维护白名单。
INSERT INTO `turbo_feed_2`.`user_3` (`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `status`, `role`, `created_at`, `updated_at`)
VALUES (1000000000000000002, '13700137000', '$2a$10$VTw0mKD3rQd2BnXAtOyhjunNcufaid1bSfdyDTk23X9HiRpMghqHi', 'DemoReviewer', '', 1, 'REVIEWER', NOW(), NOW());

-- 普通用户账号：abs(hashCode(1000000000000000001))%4 = 2 → user_2 → ds_0(turbo_feed_1)
INSERT INTO `turbo_feed_1`.`user_2` (`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `status`, `role`, `created_at`, `updated_at`)
VALUES (1000000000000000001, '13900139000', '$2a$10$VTw0mKD3rQd2BnXAtOyhjunNcufaid1bSfdyDTk23X9HiRpMghqHi', 'DemoUser', '', 1, 'USER', NOW(), NOW());

-- 路由表种子：与上面三个演示账号一一对应（不写也能登录——代码会广播兜底并惰性回填，
-- 但写上可让演示环境从第一次起就走单分片路径）。
INSERT IGNORE INTO `turbo_feed_1`.`user_phone_router` (`phone`, `user_id`) VALUES
  ('13800138000', 1000000000000000000),
  ('13700137000', 1000000000000000002),
  ('13900139000', 1000000000000000001);


-- ==================== 内容标签表（media_tag_0..media_127，分片键 post_id，HASH_MOD 128）====================
-- 2 库 × 64 表 = 128 物理表；偶数下标落 ds_0(turbo_feed_1)、奇数下标落 ds_1(turbo_feed_2)。
-- 与 shardingsphere-config-128.yaml / shardingsphere-config-local.yml 的 media_tag 规则一一对应。
-- 内容标签持久冷存（Redis 为读源，本表可重建）。
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

-- ==================== 关注关系分片表（P0-2，autoTables + HASH_MOD(128)）====================
-- 分片键：user_follow.user_id / user_fans.author_id；偶数下标落 turbo_feed_1(ds_0)，奇数落 turbo_feed_2(ds_1)。
-- Redis ZSET tf:follow:{userId} / tf:follower:{userId} 为读源(供 G6 关注流)，本表为持久化兜底(重启重建)。
CREATE TABLE `turbo_feed_1`.`user_follow_0` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_0)';
CREATE TABLE `turbo_feed_1`.`user_fans_0` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_0)';
CREATE TABLE `turbo_feed_2`.`user_follow_1` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_1)';
CREATE TABLE `turbo_feed_2`.`user_fans_1` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_1)';
CREATE TABLE `turbo_feed_1`.`user_follow_2` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_2)';
CREATE TABLE `turbo_feed_1`.`user_fans_2` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_2)';
CREATE TABLE `turbo_feed_2`.`user_follow_3` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_3)';
CREATE TABLE `turbo_feed_2`.`user_fans_3` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_3)';
CREATE TABLE `turbo_feed_1`.`user_follow_4` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_4)';
CREATE TABLE `turbo_feed_1`.`user_fans_4` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_4)';
CREATE TABLE `turbo_feed_2`.`user_follow_5` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_5)';
CREATE TABLE `turbo_feed_2`.`user_fans_5` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_5)';
CREATE TABLE `turbo_feed_1`.`user_follow_6` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_6)';
CREATE TABLE `turbo_feed_1`.`user_fans_6` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_6)';
CREATE TABLE `turbo_feed_2`.`user_follow_7` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_7)';
CREATE TABLE `turbo_feed_2`.`user_fans_7` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_7)';
CREATE TABLE `turbo_feed_1`.`user_follow_8` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_8)';
CREATE TABLE `turbo_feed_1`.`user_fans_8` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_8)';
CREATE TABLE `turbo_feed_2`.`user_follow_9` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_9)';
CREATE TABLE `turbo_feed_2`.`user_fans_9` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_9)';
CREATE TABLE `turbo_feed_1`.`user_follow_10` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_10)';
CREATE TABLE `turbo_feed_1`.`user_fans_10` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_10)';
CREATE TABLE `turbo_feed_2`.`user_follow_11` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_11)';
CREATE TABLE `turbo_feed_2`.`user_fans_11` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_11)';
CREATE TABLE `turbo_feed_1`.`user_follow_12` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_12)';
CREATE TABLE `turbo_feed_1`.`user_fans_12` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_12)';
CREATE TABLE `turbo_feed_2`.`user_follow_13` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_13)';
CREATE TABLE `turbo_feed_2`.`user_fans_13` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_13)';
CREATE TABLE `turbo_feed_1`.`user_follow_14` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_14)';
CREATE TABLE `turbo_feed_1`.`user_fans_14` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_14)';
CREATE TABLE `turbo_feed_2`.`user_follow_15` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_15)';
CREATE TABLE `turbo_feed_2`.`user_fans_15` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_15)';
CREATE TABLE `turbo_feed_1`.`user_follow_16` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_16)';
CREATE TABLE `turbo_feed_1`.`user_fans_16` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_16)';
CREATE TABLE `turbo_feed_2`.`user_follow_17` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_17)';
CREATE TABLE `turbo_feed_2`.`user_fans_17` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_17)';
CREATE TABLE `turbo_feed_1`.`user_follow_18` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_18)';
CREATE TABLE `turbo_feed_1`.`user_fans_18` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_18)';
CREATE TABLE `turbo_feed_2`.`user_follow_19` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_19)';
CREATE TABLE `turbo_feed_2`.`user_fans_19` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_19)';
CREATE TABLE `turbo_feed_1`.`user_follow_20` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_20)';
CREATE TABLE `turbo_feed_1`.`user_fans_20` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_20)';
CREATE TABLE `turbo_feed_2`.`user_follow_21` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_21)';
CREATE TABLE `turbo_feed_2`.`user_fans_21` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_21)';
CREATE TABLE `turbo_feed_1`.`user_follow_22` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_22)';
CREATE TABLE `turbo_feed_1`.`user_fans_22` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_22)';
CREATE TABLE `turbo_feed_2`.`user_follow_23` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_23)';
CREATE TABLE `turbo_feed_2`.`user_fans_23` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_23)';
CREATE TABLE `turbo_feed_1`.`user_follow_24` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_24)';
CREATE TABLE `turbo_feed_1`.`user_fans_24` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_24)';
CREATE TABLE `turbo_feed_2`.`user_follow_25` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_25)';
CREATE TABLE `turbo_feed_2`.`user_fans_25` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_25)';
CREATE TABLE `turbo_feed_1`.`user_follow_26` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_26)';
CREATE TABLE `turbo_feed_1`.`user_fans_26` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_26)';
CREATE TABLE `turbo_feed_2`.`user_follow_27` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_27)';
CREATE TABLE `turbo_feed_2`.`user_fans_27` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_27)';
CREATE TABLE `turbo_feed_1`.`user_follow_28` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_28)';
CREATE TABLE `turbo_feed_1`.`user_fans_28` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_28)';
CREATE TABLE `turbo_feed_2`.`user_follow_29` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_29)';
CREATE TABLE `turbo_feed_2`.`user_fans_29` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_29)';
CREATE TABLE `turbo_feed_1`.`user_follow_30` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_30)';
CREATE TABLE `turbo_feed_1`.`user_fans_30` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_30)';
CREATE TABLE `turbo_feed_2`.`user_follow_31` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_31)';
CREATE TABLE `turbo_feed_2`.`user_fans_31` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_31)';
CREATE TABLE `turbo_feed_1`.`user_follow_32` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_32)';
CREATE TABLE `turbo_feed_1`.`user_fans_32` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_32)';
CREATE TABLE `turbo_feed_2`.`user_follow_33` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_33)';
CREATE TABLE `turbo_feed_2`.`user_fans_33` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_33)';
CREATE TABLE `turbo_feed_1`.`user_follow_34` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_34)';
CREATE TABLE `turbo_feed_1`.`user_fans_34` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_34)';
CREATE TABLE `turbo_feed_2`.`user_follow_35` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_35)';
CREATE TABLE `turbo_feed_2`.`user_fans_35` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_35)';
CREATE TABLE `turbo_feed_1`.`user_follow_36` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_36)';
CREATE TABLE `turbo_feed_1`.`user_fans_36` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_36)';
CREATE TABLE `turbo_feed_2`.`user_follow_37` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_37)';
CREATE TABLE `turbo_feed_2`.`user_fans_37` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_37)';
CREATE TABLE `turbo_feed_1`.`user_follow_38` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_38)';
CREATE TABLE `turbo_feed_1`.`user_fans_38` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_38)';
CREATE TABLE `turbo_feed_2`.`user_follow_39` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_39)';
CREATE TABLE `turbo_feed_2`.`user_fans_39` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_39)';
CREATE TABLE `turbo_feed_1`.`user_follow_40` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_40)';
CREATE TABLE `turbo_feed_1`.`user_fans_40` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_40)';
CREATE TABLE `turbo_feed_2`.`user_follow_41` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_41)';
CREATE TABLE `turbo_feed_2`.`user_fans_41` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_41)';
CREATE TABLE `turbo_feed_1`.`user_follow_42` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_42)';
CREATE TABLE `turbo_feed_1`.`user_fans_42` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_42)';
CREATE TABLE `turbo_feed_2`.`user_follow_43` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_43)';
CREATE TABLE `turbo_feed_2`.`user_fans_43` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_43)';
CREATE TABLE `turbo_feed_1`.`user_follow_44` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_44)';
CREATE TABLE `turbo_feed_1`.`user_fans_44` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_44)';
CREATE TABLE `turbo_feed_2`.`user_follow_45` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_45)';
CREATE TABLE `turbo_feed_2`.`user_fans_45` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_45)';
CREATE TABLE `turbo_feed_1`.`user_follow_46` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_46)';
CREATE TABLE `turbo_feed_1`.`user_fans_46` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_46)';
CREATE TABLE `turbo_feed_2`.`user_follow_47` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_47)';
CREATE TABLE `turbo_feed_2`.`user_fans_47` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_47)';
CREATE TABLE `turbo_feed_1`.`user_follow_48` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_48)';
CREATE TABLE `turbo_feed_1`.`user_fans_48` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_48)';
CREATE TABLE `turbo_feed_2`.`user_follow_49` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_49)';
CREATE TABLE `turbo_feed_2`.`user_fans_49` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_49)';
CREATE TABLE `turbo_feed_1`.`user_follow_50` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_50)';
CREATE TABLE `turbo_feed_1`.`user_fans_50` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_50)';
CREATE TABLE `turbo_feed_2`.`user_follow_51` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_51)';
CREATE TABLE `turbo_feed_2`.`user_fans_51` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_51)';
CREATE TABLE `turbo_feed_1`.`user_follow_52` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_52)';
CREATE TABLE `turbo_feed_1`.`user_fans_52` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_52)';
CREATE TABLE `turbo_feed_2`.`user_follow_53` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_53)';
CREATE TABLE `turbo_feed_2`.`user_fans_53` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_53)';
CREATE TABLE `turbo_feed_1`.`user_follow_54` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_54)';
CREATE TABLE `turbo_feed_1`.`user_fans_54` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_54)';
CREATE TABLE `turbo_feed_2`.`user_follow_55` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_55)';
CREATE TABLE `turbo_feed_2`.`user_fans_55` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_55)';
CREATE TABLE `turbo_feed_1`.`user_follow_56` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_56)';
CREATE TABLE `turbo_feed_1`.`user_fans_56` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_56)';
CREATE TABLE `turbo_feed_2`.`user_follow_57` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_57)';
CREATE TABLE `turbo_feed_2`.`user_fans_57` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_57)';
CREATE TABLE `turbo_feed_1`.`user_follow_58` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_58)';
CREATE TABLE `turbo_feed_1`.`user_fans_58` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_58)';
CREATE TABLE `turbo_feed_2`.`user_follow_59` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_59)';
CREATE TABLE `turbo_feed_2`.`user_fans_59` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_59)';
CREATE TABLE `turbo_feed_1`.`user_follow_60` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_60)';
CREATE TABLE `turbo_feed_1`.`user_fans_60` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_60)';
CREATE TABLE `turbo_feed_2`.`user_follow_61` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_61)';
CREATE TABLE `turbo_feed_2`.`user_fans_61` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_61)';
CREATE TABLE `turbo_feed_1`.`user_follow_62` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_62)';
CREATE TABLE `turbo_feed_1`.`user_fans_62` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_62)';
CREATE TABLE `turbo_feed_2`.`user_follow_63` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_63)';
CREATE TABLE `turbo_feed_2`.`user_fans_63` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_63)';
CREATE TABLE `turbo_feed_1`.`user_follow_64` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_64)';
CREATE TABLE `turbo_feed_1`.`user_fans_64` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_64)';
CREATE TABLE `turbo_feed_2`.`user_follow_65` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_65)';
CREATE TABLE `turbo_feed_2`.`user_fans_65` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_65)';
CREATE TABLE `turbo_feed_1`.`user_follow_66` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_66)';
CREATE TABLE `turbo_feed_1`.`user_fans_66` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_66)';
CREATE TABLE `turbo_feed_2`.`user_follow_67` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_67)';
CREATE TABLE `turbo_feed_2`.`user_fans_67` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_67)';
CREATE TABLE `turbo_feed_1`.`user_follow_68` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_68)';
CREATE TABLE `turbo_feed_1`.`user_fans_68` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_68)';
CREATE TABLE `turbo_feed_2`.`user_follow_69` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_69)';
CREATE TABLE `turbo_feed_2`.`user_fans_69` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_69)';
CREATE TABLE `turbo_feed_1`.`user_follow_70` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_70)';
CREATE TABLE `turbo_feed_1`.`user_fans_70` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_70)';
CREATE TABLE `turbo_feed_2`.`user_follow_71` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_71)';
CREATE TABLE `turbo_feed_2`.`user_fans_71` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_71)';
CREATE TABLE `turbo_feed_1`.`user_follow_72` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_72)';
CREATE TABLE `turbo_feed_1`.`user_fans_72` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_72)';
CREATE TABLE `turbo_feed_2`.`user_follow_73` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_73)';
CREATE TABLE `turbo_feed_2`.`user_fans_73` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_73)';
CREATE TABLE `turbo_feed_1`.`user_follow_74` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_74)';
CREATE TABLE `turbo_feed_1`.`user_fans_74` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_74)';
CREATE TABLE `turbo_feed_2`.`user_follow_75` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_75)';
CREATE TABLE `turbo_feed_2`.`user_fans_75` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_75)';
CREATE TABLE `turbo_feed_1`.`user_follow_76` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_76)';
CREATE TABLE `turbo_feed_1`.`user_fans_76` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_76)';
CREATE TABLE `turbo_feed_2`.`user_follow_77` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_77)';
CREATE TABLE `turbo_feed_2`.`user_fans_77` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_77)';
CREATE TABLE `turbo_feed_1`.`user_follow_78` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_78)';
CREATE TABLE `turbo_feed_1`.`user_fans_78` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_78)';
CREATE TABLE `turbo_feed_2`.`user_follow_79` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_79)';
CREATE TABLE `turbo_feed_2`.`user_fans_79` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_79)';
CREATE TABLE `turbo_feed_1`.`user_follow_80` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_80)';
CREATE TABLE `turbo_feed_1`.`user_fans_80` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_80)';
CREATE TABLE `turbo_feed_2`.`user_follow_81` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_81)';
CREATE TABLE `turbo_feed_2`.`user_fans_81` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_81)';
CREATE TABLE `turbo_feed_1`.`user_follow_82` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_82)';
CREATE TABLE `turbo_feed_1`.`user_fans_82` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_82)';
CREATE TABLE `turbo_feed_2`.`user_follow_83` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_83)';
CREATE TABLE `turbo_feed_2`.`user_fans_83` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_83)';
CREATE TABLE `turbo_feed_1`.`user_follow_84` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_84)';
CREATE TABLE `turbo_feed_1`.`user_fans_84` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_84)';
CREATE TABLE `turbo_feed_2`.`user_follow_85` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_85)';
CREATE TABLE `turbo_feed_2`.`user_fans_85` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_85)';
CREATE TABLE `turbo_feed_1`.`user_follow_86` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_86)';
CREATE TABLE `turbo_feed_1`.`user_fans_86` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_86)';
CREATE TABLE `turbo_feed_2`.`user_follow_87` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_87)';
CREATE TABLE `turbo_feed_2`.`user_fans_87` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_87)';
CREATE TABLE `turbo_feed_1`.`user_follow_88` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_88)';
CREATE TABLE `turbo_feed_1`.`user_fans_88` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_88)';
CREATE TABLE `turbo_feed_2`.`user_follow_89` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_89)';
CREATE TABLE `turbo_feed_2`.`user_fans_89` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_89)';
CREATE TABLE `turbo_feed_1`.`user_follow_90` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_90)';
CREATE TABLE `turbo_feed_1`.`user_fans_90` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_90)';
CREATE TABLE `turbo_feed_2`.`user_follow_91` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_91)';
CREATE TABLE `turbo_feed_2`.`user_fans_91` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_91)';
CREATE TABLE `turbo_feed_1`.`user_follow_92` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_92)';
CREATE TABLE `turbo_feed_1`.`user_fans_92` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_92)';
CREATE TABLE `turbo_feed_2`.`user_follow_93` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_93)';
CREATE TABLE `turbo_feed_2`.`user_fans_93` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_93)';
CREATE TABLE `turbo_feed_1`.`user_follow_94` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_94)';
CREATE TABLE `turbo_feed_1`.`user_fans_94` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_94)';
CREATE TABLE `turbo_feed_2`.`user_follow_95` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_95)';
CREATE TABLE `turbo_feed_2`.`user_fans_95` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_95)';
CREATE TABLE `turbo_feed_1`.`user_follow_96` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_96)';
CREATE TABLE `turbo_feed_1`.`user_fans_96` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_96)';
CREATE TABLE `turbo_feed_2`.`user_follow_97` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_97)';
CREATE TABLE `turbo_feed_2`.`user_fans_97` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_97)';
CREATE TABLE `turbo_feed_1`.`user_follow_98` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_98)';
CREATE TABLE `turbo_feed_1`.`user_fans_98` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_98)';
CREATE TABLE `turbo_feed_2`.`user_follow_99` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_99)';
CREATE TABLE `turbo_feed_2`.`user_fans_99` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_99)';
CREATE TABLE `turbo_feed_1`.`user_follow_100` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_100)';
CREATE TABLE `turbo_feed_1`.`user_fans_100` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_100)';
CREATE TABLE `turbo_feed_2`.`user_follow_101` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_101)';
CREATE TABLE `turbo_feed_2`.`user_fans_101` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_101)';
CREATE TABLE `turbo_feed_1`.`user_follow_102` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_102)';
CREATE TABLE `turbo_feed_1`.`user_fans_102` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_102)';
CREATE TABLE `turbo_feed_2`.`user_follow_103` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_103)';
CREATE TABLE `turbo_feed_2`.`user_fans_103` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_103)';
CREATE TABLE `turbo_feed_1`.`user_follow_104` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_104)';
CREATE TABLE `turbo_feed_1`.`user_fans_104` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_104)';
CREATE TABLE `turbo_feed_2`.`user_follow_105` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_105)';
CREATE TABLE `turbo_feed_2`.`user_fans_105` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_105)';
CREATE TABLE `turbo_feed_1`.`user_follow_106` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_106)';
CREATE TABLE `turbo_feed_1`.`user_fans_106` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_106)';
CREATE TABLE `turbo_feed_2`.`user_follow_107` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_107)';
CREATE TABLE `turbo_feed_2`.`user_fans_107` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_107)';
CREATE TABLE `turbo_feed_1`.`user_follow_108` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_108)';
CREATE TABLE `turbo_feed_1`.`user_fans_108` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_108)';
CREATE TABLE `turbo_feed_2`.`user_follow_109` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_109)';
CREATE TABLE `turbo_feed_2`.`user_fans_109` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_109)';
CREATE TABLE `turbo_feed_1`.`user_follow_110` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_110)';
CREATE TABLE `turbo_feed_1`.`user_fans_110` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_110)';
CREATE TABLE `turbo_feed_2`.`user_follow_111` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_111)';
CREATE TABLE `turbo_feed_2`.`user_fans_111` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_111)';
CREATE TABLE `turbo_feed_1`.`user_follow_112` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_112)';
CREATE TABLE `turbo_feed_1`.`user_fans_112` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_112)';
CREATE TABLE `turbo_feed_2`.`user_follow_113` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_113)';
CREATE TABLE `turbo_feed_2`.`user_fans_113` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_113)';
CREATE TABLE `turbo_feed_1`.`user_follow_114` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_114)';
CREATE TABLE `turbo_feed_1`.`user_fans_114` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_114)';
CREATE TABLE `turbo_feed_2`.`user_follow_115` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_115)';
CREATE TABLE `turbo_feed_2`.`user_fans_115` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_115)';
CREATE TABLE `turbo_feed_1`.`user_follow_116` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_116)';
CREATE TABLE `turbo_feed_1`.`user_fans_116` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_116)';
CREATE TABLE `turbo_feed_2`.`user_follow_117` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_117)';
CREATE TABLE `turbo_feed_2`.`user_fans_117` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_117)';
CREATE TABLE `turbo_feed_1`.`user_follow_118` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_118)';
CREATE TABLE `turbo_feed_1`.`user_fans_118` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_118)';
CREATE TABLE `turbo_feed_2`.`user_follow_119` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_119)';
CREATE TABLE `turbo_feed_2`.`user_fans_119` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_119)';
CREATE TABLE `turbo_feed_1`.`user_follow_120` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_120)';
CREATE TABLE `turbo_feed_1`.`user_fans_120` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_120)';
CREATE TABLE `turbo_feed_2`.`user_follow_121` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_121)';
CREATE TABLE `turbo_feed_2`.`user_fans_121` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_121)';
CREATE TABLE `turbo_feed_1`.`user_follow_122` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_122)';
CREATE TABLE `turbo_feed_1`.`user_fans_122` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_122)';
CREATE TABLE `turbo_feed_2`.`user_follow_123` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_123)';
CREATE TABLE `turbo_feed_2`.`user_fans_123` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_123)';
CREATE TABLE `turbo_feed_1`.`user_follow_124` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_124)';
CREATE TABLE `turbo_feed_1`.`user_fans_124` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_124)';
CREATE TABLE `turbo_feed_2`.`user_follow_125` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_125)';
CREATE TABLE `turbo_feed_2`.`user_fans_125` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_125)';
CREATE TABLE `turbo_feed_1`.`user_follow_126` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_1.user_follow_126)';
CREATE TABLE `turbo_feed_1`.`user_fans_126` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_1.user_fans_126)';
CREATE TABLE `turbo_feed_2`.`user_follow_127` (
  `user_id`    BIGINT       NOT NULL                COMMENT '关注者用户ID, 分片键',
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关(对方也关注了我)',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`, `author_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表(我关注了谁, 物理分片 turbo_feed_2.user_follow_127)';
CREATE TABLE `turbo_feed_2`.`user_fans_127` (
  `author_id`  BIGINT       NOT NULL                COMMENT '被关注作者ID, 分片键',
  `user_id`    BIGINT       NOT NULL                COMMENT '粉丝用户ID',
  `mutual`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=互关',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1=关注中 0=已取消(逻辑删除)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '关注时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`author_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝关系表(谁关注了我, 物理分片 turbo_feed_2.user_fans_127)';
