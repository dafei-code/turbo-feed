-- =============================================================================
-- turbo-feed 用户表建表语句（分库分表）
-- -----------------------------------------------------------------------------
-- 分片方案（与 shardingsphere-config.yaml 严格对应）：
--   逻辑表 user，分片键 id（用户全局唯一 ID，雪花 Long）
--   路由：db = hash(id) % 2    -> ds_0(turbo_feed_1) / ds_1(turbo_feed_2)
--         table = hash(id) % 2 -> user_0 / user_1
--   物理表共 4 张：turbo_feed_1.user_0|user_1、turbo_feed_2.user_0|user_1
--
-- 建表前置：先建库（每个数据源对应一个物理库）
--   CREATE DATABASE IF NOT EXISTS turbo_feed_1 DEFAULT CHARSET utf8mb4;
--   CREATE DATABASE IF NOT EXISTS turbo_feed_2 DEFAULT CHARSET utf8mb4;
-- 然后在各自库内执行对应的 user_0 / user_1（下表已按库分组给出）。
--
-- ⚠️ 分片唯一性坑（务必阅读）：
--   uk_phone 唯一索引只在「单张物理表」内生效，ShardingSphere 不会跨分片去重。
--   若两个不同 id 的用户注册了相同手机号，且恰巧落在不同分片，唯一约束各自成立、
--   都能插入成功 —— 跨分片出现重复手机号（业务不允许）。
--   解决（本工程采用方案 1，详见文末 user_phone_router）：
--     1) 注册 / 改手机号时，先写不分片路由表 user_phone_router(phone_hash -> id + 分片号)
--        做全局去重 + 定位分片，再写分片 user 表；登录也先查路由表精准命中，避免
--        WHERE phone=? 无分片键导致的全分片广播；或
--     2) 登录统一走 id（JWT 里带的是 userId / 手机号），phone 仅作登录标识、不用于
--        定位分片时，需应用层对 phone 做分布式锁 / 查重（先全分片扫描，成本高，不推荐）。
--   本表保留 uk_phone 作为单分片内防护，全局唯一由 user_phone_router 上层保证。
-- =============================================================================

-- -------------------- 库 1：turbo_feed_1（ds_0）--------------------
CREATE TABLE `turbo_feed_1`.`user_0` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一(跨分片唯一见文件头)',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt/argon2), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(物理分片 turbo_feed_1.user_0)';

CREATE TABLE `turbo_feed_1`.`user_1` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一(跨分片唯一见文件头)',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt/argon2), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(物理分片 turbo_feed_1.user_1)';

-- -------------------- 库 2：turbo_feed_2（ds_1）--------------------
CREATE TABLE `turbo_feed_2`.`user_0` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一(跨分片唯一见文件头)',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt/argon2), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(物理分片 turbo_feed_2.user_0)';

CREATE TABLE `turbo_feed_2`.`user_1` (
  `id`            BIGINT       NOT NULL                COMMENT '用户全局唯一ID(雪花算法), 分片键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '登录手机号, 全局唯一(跨分片唯一见文件头)',
  `password_hash` VARCHAR(128) NOT NULL                COMMENT '密码哈希(bcrypt/argon2), 不存明文',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '头像URL(对象存储)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=正常 2=禁用 3=待激活',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP               COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(物理分片 turbo_feed_2.user_1)';

-- =============================================================================
-- 手机号路由表（不分片）：登录定位 + 全局唯一去重
-- -----------------------------------------------------------------------------
-- 作用：phone 不是分片键(id)，直接 `WHERE phone=?` 会触发全分片广播；且 uk_phone 只在
--       单分片内生效，跨分片无法保证全局唯一。本表不分片，集中存 phone_hash -> user_id
--       + 物理分片号(ds_?/user_?)，用于：
--         (1) 注册 / 改手机号时全局去重（先写路由表，冲突即拒，再写分片 user 表）；
--         (2) 登录时先按 phone_hash 查本表拿到分片号，再用 id 精准命中 user 表，
--             避免无分片键导致的全分片广播。
-- 部署：本表必须放在「不分片」数据源（单库单表），可由 ShardingSphere `!SINGLE` 规则托管，
--       或由应用直连独立路由库（ds_router）。当前 demo 阶段不连表，仅预留 DDL。
-- =============================================================================

CREATE TABLE `user_phone_router` (
  `phone_hash`    BIGINT       NOT NULL                COMMENT '手机号哈希(CRC32/自定义), 路由与去重键',
  `phone`         VARCHAR(20)  NOT NULL                COMMENT '明文手机号(注册回显/校验, 可按需加密)',
  `user_id`       BIGINT       NOT NULL                COMMENT '关联 user.id(分片键)',
  `ds_index`      TINYINT      NOT NULL                COMMENT '物理库序号 0/1 -> ds_0/ds_1',
  `table_index`   TINYINT      NOT NULL                COMMENT '物理表序号 0/1 -> user_0/user_1',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`phone_hash`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机号路由表(不分片, 登录定位+全局去重)';

