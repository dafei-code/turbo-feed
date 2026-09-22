-- =============================================================================
-- turbo-feed 媒体元数据表建表语句（分库分表，与 user 表同策略）
-- -----------------------------------------------------------------------------
-- 分片方案（与 shardingsphere-config.yaml 严格对应）：
--   逻辑表 media，分片键 user_id（归属用户 ID，与 user 表分片键 id 同值）
--   路由：db    = hash(user_id) % 2 -> ds_0(turbo_feed_1) / ds_1(turbo_feed_2)
--         table = hash(user_id) % 2 -> media_0 / media_1
--   物理表共 4 张（⚠️ 全局连续编号，非每库从 0 重编）：
--     turbo_feed_1(ds_0) 拿偶数下标 media_0 / media_2
--     turbo_feed_2(ds_1) 拿奇数下标 media_1 / media_3
--   2026-09-21 修正：旧版写成每库 media_0|media_1，与 autoTables 推导的物理表名不符，
--     照旧版建表会让 4 张表里有一半永远路由不到（见 changelog 0041）。
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
-- ⚠️ 连接账号：shardingsphere-config.yaml 中 ds_0/ds_1 的口令已占位符化（环境变量
--    TURBOFEED_DB_PASSWORD，仓库内无默认值，见 changelog 0048）。本机开发请先 export
--    该变量再启动，否则应用启动后首次访问 media 表会因认证失败连不上。
-- =============================================================================

-- -------------------- 库 turbo_feed_1 --------------------
CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_0` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图;

CREATE TABLE IF NOT EXISTS `turbo_feed_1`.`media_2` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图;

-- -------------------- 库 turbo_feed_2 --------------------
CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_1` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图;

CREATE TABLE IF NOT EXISTS `turbo_feed_2`.`media_3` (
  `media_id`   VARCHAR(255) NOT NULL                COMMENT '内容唯一标识(业务主键)',
  `user_id`    BIGINT        NOT NULL                COMMENT '归属用户ID, 分片键',
  `url`        VARCHAR(512) NOT NULL                COMMENT '可访问地址',
  `status`     TINYINT       NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=APPROVED 2=REJECTED',
  `media_type` VARCHAR(16)   NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE/VIDEO',
  `caption`     VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '描述/标题（抖音式文案；解析后结构化标记在 caption_mark）',
  `caption_mark` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '解析后的结构化标记 JSON：@用户 / #话题 / [image:idx:filename]，前端直接消费',
  `post_id`    VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '帖子ID(一次上传批次=一帖多图;
