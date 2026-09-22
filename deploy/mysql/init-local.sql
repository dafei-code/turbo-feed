-- =============================================================================
-- turbo-feed 本地 MySQL 初始化脚本（幂等、可重复执行）
-- -----------------------------------------------------------------------------
-- 作用：建 turbo_feed_1 / turbo_feed_2 两库 + 物理分片表，供 ShardingSphere 路由。
--       与 tf-gateway 的 shardingsphere-config.yaml 中 ds_0/ds_1 一一对应
--       （root/123456，127.0.0.1:3306）。
--
-- ⚠️ 物理表命名必须与 ShardingSphere autoTables + HASH_MOD 的推断一致：
--    sharding-count=4、2 个 actualDataSources(ds_0,ds_1) → 推断 4 张物理表
--    user_0..user_3（media 同理），按 actualDataSources.get(i % ds数) 落到库：
--      ds_0(turbo_feed_1): user_0, user_2, media_0, media_2
--      ds_1(turbo_feed_2): user_1, user_3, media_1, media_3
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
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_2)';

-- ==================== 分片表（抖音式审核骨架）：account_credit / report / appeal ====================
-- 三张表不再走单表，改为与 user/media 同策略的 HASH_MOD 分片（sharding-count=4，2 库），
-- 物理表命名 <逻辑表>_<0..3>，偶数下标(_0/_2)落 ds_0、奇数下标(_1/_3)落 ds_1，与 user/media 一致。
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
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_post` (`user_id`, `post_id`, `seq`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_3)';

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

-- ==================== 评论表（comment_0..comment_3，分片键 media_id，HASH_MOD 4 节点） ====================
-- 设计：comment 独立于 media（独立包、独立读写路径），按 media_id 分片保证
--       「读某条内容下所有评论」单分片命中，与 media(user_id) 分片键正交——不依赖
--       content 所在库即可定位评论。楼中楼用 root_id(顶层=commentId) + parent_id(直接父)
--       双指针：一级列表 root_id=commentId，二级回复按 root_id 聚合按 created_at 排。
-- 配置见 shardingsphere-config.yaml 的 comment autoTable。
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
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表(物理分片 turbo_feed_1.comment_0)';

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
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表(物理分片 turbo_feed_1.comment_2)';

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
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表(物理分片 turbo_feed_2.comment_1)';

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
  KEY `idx_media_root_created` (`media_id`, `root_id`, `created_at`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表(物理分片 turbo_feed_2.comment_3)';

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
