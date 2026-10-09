-- =============================================================================
-- 举报类目列 report.category（P1-1 举报分类 + 同类累计）
-- -----------------------------------------------------------------------------
-- 抖音式「举报分类 → 同类累计才升级」：report 表新增 category 列（ViolationCategory code，
-- 0=OTHER），Tier1 由「举报总数≥阈值」升级为「同类举报累计≥阈值」，避免无关举报混加爆审核台。
--
-- 物理分片（hashMod count=4，2 库）：report_0/report_2 在 turbo_feed_1(ds_0)，
-- report_1/report_3 在 turbo_feed_2(ds_1)。须对 4 张物理表各 ALTER 一次。
--
-- ⚠️ 单表 ALTER（增量），非 DROP 重建；幂等靠「列已存在则跳过」——本脚本用
--     information_schema 预检，已存在则不再 ALTER，可重复执行。
-- ⚠️ 改表后必须重启网关（ShardingSphere 启动时加载元数据）。
-- =============================================================================

-- 预检函数式 ALTER：仅当列不存在时才加（MySQL 不支持 ADD COLUMN IF NOT EXISTS 原语）。
SET @sql0 = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema='turbo_feed_1' AND table_name='report_0' AND column_name='category') = 0,
    'ALTER TABLE turbo_feed_1.report_0 ADD COLUMN category TINYINT NOT NULL DEFAULT 0 COMMENT ''举报类目(ViolationCategory code,0=OTHER)''',
    'SELECT 1'));
PREPARE stmt FROM @sql0; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql2 = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema='turbo_feed_1' AND table_name='report_2' AND column_name='category') = 0,
    'ALTER TABLE turbo_feed_1.report_2 ADD COLUMN category TINYINT NOT NULL DEFAULT 0 COMMENT ''举报类目(ViolationCategory code,0=OTHER)''',
    'SELECT 1'));
PREPARE stmt FROM @sql2; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql1 = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema='turbo_feed_2' AND table_name='report_1' AND column_name='category') = 0,
    'ALTER TABLE turbo_feed_2.report_1 ADD COLUMN category TINYINT NOT NULL DEFAULT 0 COMMENT ''举报类目(ViolationCategory code,0=OTHER)''',
    'SELECT 1'));
PREPARE stmt FROM @sql1; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql3 = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema='turbo_feed_2' AND table_name='report_3' AND column_name='category') = 0,
    'ALTER TABLE turbo_feed_2.report_3 ADD COLUMN category TINYINT NOT NULL DEFAULT 0 COMMENT ''举报类目(ViolationCategory code,0=OTHER)''',
    'SELECT 1'));
PREPARE stmt FROM @sql3; EXECUTE stmt; DEALLOCATE PREPARE stmt;
