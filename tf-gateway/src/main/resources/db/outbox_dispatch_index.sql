-- ==================== 发件箱补偿扫描索引（增量迁移） ====================
-- 背景：OutboxRelay 补偿通道周期性扫描 outbox_event——
--       ① findDueIds:  WHERE status IN (PENDING,FAILED) AND next_attempt_at <= ?  → 已由 idx_dispatch(status, next_attempt_at) 覆盖；
--       ② reapStuck:    WHERE status = SENDING AND updated_at < ?                  → 原先无专用索引，退化成「status 前缀 + 全量 updated_at 过滤」。
-- 改造：补 (status, updated_at) 复合索引，让 reapStuck（回收进程崩溃残留的 SENDING 行）
--       走索引范围扫描，避免随表增长退化为全扫描。
-- 本脚本为存量库增量加索引；新库以 init-local.sql 的 CREATE TABLE 为准（已含 idx_reap）。
-- ⚠️ 执行后必须重启应用（ShardingSphere 启动期加载元数据，历史踩坑）。
-- ⚠️ outbox_event 是单表（ds_0 = turbo_feed_1），直接改物理表；生产绝不可跑 init-local.sql。
-- 注：idx_dispatch 已存在则本脚本的 ADD KEY idx_reap 为纯新增，不影响既有查询。

ALTER TABLE `turbo_feed_1`.`outbox_event`
  ADD KEY `idx_reap` (`status`, `updated_at`);
