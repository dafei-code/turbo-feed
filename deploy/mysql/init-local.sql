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
-- 说明：user_phone_router 不分片路由表本 demo 阶段未启用（注册去重走广播 COUNT），此处不建。
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
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_0)';

CREATE TABLE `turbo_feed_1`.`media_2` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_1.media_2)';

-- ==================== 单表（抖音式审核骨架）：account_credit / report / appeal ====================
-- 三张单表均落在 ds_0(turbo_feed_1)，由 ShardingSphere SINGLE 规则路由（见 shardingsphere-config.yaml）。
-- 与分片表不同，单表无物理下标，逻辑表名 == 物理表名。

-- 账号信用分级表：高信用(L2)先发后审入大池 / 普通(L1)先发后审入小池 / 低信用·新号(L0)先审后放。
CREATE TABLE `turbo_feed_1`.`account_credit` (
  `user_id`           BIGINT       NOT NULL                COMMENT '用户ID, 单表主键',
  `credit_score`      INT          NOT NULL DEFAULT 100    COMMENT '信用分(0-100), 越低越严',
  `level`             TINYINT      NOT NULL DEFAULT 1      COMMENT '信用等级 0=L0(先审后放) 1=L1(小池) 2=L2(大池)',
  `strict_queue_flag` TINYINT      NOT NULL DEFAULT 0      COMMENT '0=普通 1=加严队列(近30天有下架, 所有内容先审后放)',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号信用分级表(单表 ds_0)';

-- 用户举报表：对任意已发布内容举报写本表；高危理由由服务层 fail-closed 立即下架。
CREATE TABLE `turbo_feed_1`.`report` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '举报记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被举报内容ID',
  `reporter_user_id`  BIGINT       NOT NULL                COMMENT '举报人用户ID',
  `reason`            VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '举报理由(含涉政/暴恐/儿童等高危词→立即下架)',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=待处理 1=已确认违规 2=已驳回',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_reporter` (`reporter_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容举报表(单表 ds_0)';

-- 作者申诉表：作者对自身被驳回/下架内容申诉写本表；管理员复核翻案/维持。
CREATE TABLE `turbo_feed_1`.`appeal` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申诉记录ID',
  `media_id`          VARCHAR(255) NOT NULL                COMMENT '被申诉内容ID',
  `author_user_id`    BIGINT       NOT NULL                COMMENT '申诉作者用户ID',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=申诉中 1=翻案(恢复) 2=维持(驳回)',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申诉时间',
  PRIMARY KEY (`id`),
  KEY `idx_media_status` (`media_id`, `status`),
  KEY `idx_author` (`author_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容申诉表(单表 ds_0)';

-- ==================== 库 2：turbo_feed_2（ds_1）===================
-- ds_1 承载编号奇数下标的物理表：user_1/user_3、media_1/media_3
CREATE TABLE `turbo_feed_2`.`user_1` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
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
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_1)';

CREATE TABLE `turbo_feed_2`.`media_3` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体元数据表(物理分片 turbo_feed_2.media_3)';

-- ==================== 演示账号种子数据（DB 重建后可直接登录） ====================
-- 说明：网关登录现走 user 分片表 + BCrypt 校验；旧 application.yml 中 auth.demo-users
--      配置已废弃。重新执行本脚本后可用以下账号登录：
--   13800138000 / 123456  → 命中 admin.phones 白名单，令牌角色 ADMIN
--   13900139000 / 123456  → 普通用户，令牌角色 USER
-- 密码均为 "123456" 的 BCrypt(cost=10) 哈希，生产环境务必移除这些种子。

-- 管理员账号：id 1000000000000000000 % 4 = 0 → user_0(ds_0)
-- 密码 "123456" 的 BCrypt(cost=10) 哈希，必须使用 $2a$ 前缀（Spring BCryptPasswordEncoder/jBCrypt 仅原生接受 $2a$，
-- $2y$/$2b$ 会在 hashpw 抛 Invalid salt revision）。本机用 htpasswd -B 生成的是 $2y$，改前缀为 $2a$ 即可（纯 ASCII 短口令等价）。
INSERT INTO `turbo_feed_1`.`user_0` (`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `status`, `created_at`, `updated_at`)
VALUES (1000000000000000000, '13800138000', '$2a$10$VTw0mKD3rQd2BnXAtOyhjunNcufaid1bSfdyDTk23X9HiRpMghqHi', 'DemoAdmin', '', 1, NOW(), NOW());

-- 普通用户账号：id 1000000000000000001 % 4 = 1 → user_1(ds_1)
INSERT INTO `turbo_feed_2`.`user_1` (`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `status`, `created_at`, `updated_at`)
VALUES (1000000000000000001, '13900139000', '$2a$10$VTw0mKD3rQd2BnXAtOyhjunNcufaid1bSfdyDTk23X9HiRpMghqHi', 'DemoUser', '', 1, NOW(), NOW());
