-- =============================================================================
-- turbo-feed 迁移 0020：media 表引入「帖子(一帖多图)」分组列
-- -----------------------------------------------------------------------------
-- 背景：原实现「一次上传 N 张 = N 条互相独立的 media 记录」，前端只能一图一卡片，
--       做不到抖音式「一个帖子 9 张图轮播」。本次为 media 表引入 post_id / seq：
--       同一次上传批次的 N 张图共享一个 post_id，seq 为 0 起的帖内序号（轮播顺序）。
--
-- 目标表（4 张物理分片表，与 shardingsphere-config.yaml 的 autoTables/HASH_MOD 一致）：
--   ds_0(turbo_feed_1): media_0, media_2
--   ds_1(turbo_feed_2): media_1, media_3
--
-- 新增列：
--   post_id VARCHAR(255) NOT NULL DEFAULT ''  帖子ID（形如 post/{userId}/{uuid}）
--   seq     TINYINT      NOT NULL DEFAULT 0   帖内图片序号（0 起）
-- 新增索引：
--   idx_user_post (user_id, post_id, seq)
--     支撑「带分片键精准命中单分片」的帖内查询：
--       WHERE user_id = ? AND post_id = ? ORDER BY seq
--     分片键仍是 user_id —— 仅凭 post_id 查询会触发全分片广播，调用方必须同时带 user_id。
--
-- 历史数据兼容（重要）：
--   存量行的 post_id 取默认值 ''（空串）。应用侧约定「post_id 为空 → 视为单图帖，
--   回退用 media_id 作为帖子身份」，因此旧数据无需任何回填即可继续被查询与下架。
--   本脚本不执行 UPDATE 回填，避免在空串语义上引入额外状态。
--
-- 幂等性：
--   MySQL 8.0 的 ALTER TABLE 不支持 ADD COLUMN IF NOT EXISTS，
--   重复执行会报 "Duplicate column name 'post_id'" / "Duplicate key name 'idx_user_post'"。
--   该报错不影响已完成的变更，可安全忽略；执行前确认表已具备目标列即可跳过。
--
-- ⚠️ 执行后必须重启 tf-gateway（ShardingSphere 会在启动时缓存表元数据，
--    不重启会持续按旧列结构生成 SQL 并报「列不存在」）。
--
-- 用法（在本仓库根目录执行）：
--   mysql -uroot -p123456 < deploy/mysql/migrate_post_group.sql
-- =============================================================================

-- -------------------- ds_0：turbo_feed_1 --------------------
ALTER TABLE `turbo_feed_1`.`media_0`
  ADD COLUMN `post_id` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖)' AFTER `caption_mark`,
  ADD COLUMN `seq`     TINYINT      NOT NULL DEFAULT 0  COMMENT '帖内图片序号(0起, 决定轮播顺序)' AFTER `post_id`,
  ADD KEY `idx_user_post` (`user_id`, `post_id`, `seq`);

ALTER TABLE `turbo_feed_1`.`media_2`
  ADD COLUMN `post_id` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖)' AFTER `caption_mark`,
  ADD COLUMN `seq`     TINYINT      NOT NULL DEFAULT 0  COMMENT '帖内图片序号(0起, 决定轮播顺序)' AFTER `post_id`,
  ADD KEY `idx_user_post` (`user_id`, `post_id`, `seq`);

-- -------------------- ds_1：turbo_feed_2 --------------------
ALTER TABLE `turbo_feed_2`.`media_1`
  ADD COLUMN `post_id` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖)' AFTER `caption_mark`,
  ADD COLUMN `seq`     TINYINT      NOT NULL DEFAULT 0  COMMENT '帖内图片序号(0起, 决定轮播顺序)' AFTER `post_id`,
  ADD KEY `idx_user_post` (`user_id`, `post_id`, `seq`);

ALTER TABLE `turbo_feed_2`.`media_3`
  ADD COLUMN `post_id` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '帖子ID(一次上传批次=一帖多图; 空串=历史遗留单图帖)' AFTER `caption_mark`,
  ADD COLUMN `seq`     TINYINT      NOT NULL DEFAULT 0  COMMENT '帖内图片序号(0起, 决定轮播顺序)' AFTER `post_id`,
  ADD KEY `idx_user_post` (`user_id`, `post_id`, `seq`);

-- -------------------- 校验：4 张表应各出现 post_id / seq / idx_user_post --------------------
-- SELECT table_schema, table_name, column_name, column_type, column_default
--   FROM information_schema.columns
--  WHERE table_schema IN ('turbo_feed_1','turbo_feed_2') AND table_name LIKE 'media\_%'
--    AND column_name IN ('post_id','seq')
--  ORDER BY table_schema, table_name, ordinal_position;
