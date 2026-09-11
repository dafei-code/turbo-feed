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
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
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
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号信用分级表(物理分片 turbo_feed_1.account_credit_0)';

CREATE TABLE `turbo_feed_1`.`account_credit_2` (
  `user_id`           BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id), 与 user/media 同片',
  `credit_score`      INT          NOT NULL DEFAULT 100    COMMENT '信用分(0-100), 越低越严',
  `level`             TINYINT      NOT NULL DEFAULT 1      COMMENT '信用等级 0=L0(先审后放) 1=L1(小池) 2=L2(大池)',
  `strict_queue_flag` TINYINT      NOT NULL DEFAULT 0      COMMENT '0=普通 1=加严队列(近30天有下架, 所有内容先审后放)',
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
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `file_size`  BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '上传时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`media_id`),
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
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号信用分级表(物理分片 turbo_feed_2.account_credit_1)';

CREATE TABLE `turbo_feed_2`.`account_credit_3` (
  `user_id`           BIGINT       NOT NULL                COMMENT '用户ID, 分片键(user_id), 与 user/media 同片',
  `credit_score`      INT          NOT NULL DEFAULT 100    COMMENT '信用分(0-100), 越低越严',
  `level`             TINYINT      NOT NULL DEFAULT 1      COMMENT '信用等级 0=L0(先审后放) 1=L1(小池) 2=L2(大池)',
  `strict_queue_flag` TINYINT      NOT NULL DEFAULT 0      COMMENT '0=普通 1=加严队列(近30天有下架, 所有内容先审后放)',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号信用分级表(物理分片 turbo_feed_2.account_credit_3)';

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

-- ==================== 敏感词库（sensitive_word，单表放 ds_0，ShardingSphere 未配置规则的表走默认 ds_0） ====================
-- 词库小（KB~MB 级），单表足够；写少读多（启动 + 30s 定时 + 手动触发全量加载到 AC 自动机）。
-- revision 单调递增，节点用「本地 revision vs DB MAX(revision)」判断是否需要重载；
-- 检测到变更则全量重建 AC（词库 < 10k 时全量重建成本 < 1ms，生产大词库可演进为增量合并）。
-- category 用于分类（政治/色情/广告/自定义），分类 Trie 后续可扩展为多棵子树。
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

-- ==================== 演示账号种子数据（DB 重建后可直接登录） ====================
-- 说明：网关登录现走 user 分片表 + BCrypt 校验；旧 application.yml 中 auth.demo-users
--      配置已废弃。重新执行本脚本后可用以下账号登录：
--   13800138000 / 123456  → role=ADMIN（系统管理员，进 admin.html，角色来自 user 表 role 列）
--   13700137000 / 123456  → role=REVIEWER（审核员，进 review.html，角色来自 user 表 role 列）
--   13900139000 / 123456  → role=USER（普通用户，进 user.html，角色来自 user 表 role 列）
-- 密码均为 "123456" 的 BCrypt(cost=10) 哈希，生产环境务必移除这些种子。

-- 管理员账号：id 1000000000000000000 % 4 = 0 → user_0(ds_0)
-- 密码 "123456" 的 BCrypt(cost=10) 哈希，必须使用 $2a$ 前缀（Spring BCryptPasswordEncoder/jBCrypt 仅原生接受 $2a$，
-- $2y$/$2b$ 会在 hashpw 抛 Invalid salt revision）。本机用 htpasswd -B 生成的是 $2y$，改前缀为 $2a$ 即可（纯 ASCII 短口令等价）。
INSERT INTO `turbo_feed_1`.`user_0` (`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `status`, `role`, `created_at`, `updated_at`)
VALUES (1000000000000000000, '13800138000', '$2a$10$VTw0mKD3rQd2BnXAtOyhjunNcufaid1bSfdyDTk23X9HiRpMghqHi', 'DemoAdmin', '', 1, 'ADMIN', NOW(), NOW());

-- 审核员账号：id 1000000000000000002 % 4 = 2 → user_2(ds_0)
-- 角色直接写在 user 表 role 列（真 RBAC），登录时 AuthService 读库派生 JWT，不在配置里维护白名单。
INSERT INTO `turbo_feed_1`.`user_2` (`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `status`, `role`, `created_at`, `updated_at`)
VALUES (1000000000000000002, '13700137000', '$2a$10$VTw0mKD3rQd2BnXAtOyhjunNcufaid1bSfdyDTk23X9HiRpMghqHi', 'DemoReviewer', '', 1, 'REVIEWER', NOW(), NOW());

-- 普通用户账号：id 1000000000000000001 % 4 = 1 → user_1(ds_1)
INSERT INTO `turbo_feed_2`.`user_1` (`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `status`, `role`, `created_at`, `updated_at`)
VALUES (1000000000000000001, '13900139000', '$2a$10$VTw0mKD3rQd2BnXAtOyhjunNcufaid1bSfdyDTk23X9HiRpMghqHi', 'DemoUser', '', 1, 'USER', NOW(), NOW());
