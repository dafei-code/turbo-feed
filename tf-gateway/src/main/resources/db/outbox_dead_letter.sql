-- ==================== 发件箱死信重投（增量迁移） ====================
-- 背景：原状态机到 DEAD 即终止，既不复投也无告警，at-least-once 在 DEAD 处断裂；
--       且 TIMELINE_REMOVE(下架) 比 TIMELINE_APPEND(入流) 严重（下架内容持续展示）。
-- 改造：OutboxDeadLetterSweeper 周期扫 DEAD 行，未达重投上限则重新 open 成 PENDING（带冷却），
--       交给既有 OutboxRelay 投递；首批判死即触发 DeadLetterAlert（REMOVE 高严重度）。
-- 本脚本为存量库增量加列；新库以 init-local.sql 的 CREATE TABLE 为准（已含 dead_count）。
-- ⚠️ 执行后必须重启应用（ShardingSphere 启动期加载元数据，历史踩坑）。
-- ⚠️ outbox_event 是单表（ds_0 = turbo_feed_1），直接改物理表；生产绝不可跑 init-local.sql。

ALTER TABLE `turbo_feed_1`.`outbox_event`
  ADD COLUMN `dead_count` INT NOT NULL DEFAULT 0
  COMMENT '死信重投次数, 达 max-dead-redeliveries 后永久终态' AFTER `max_attempts`;
