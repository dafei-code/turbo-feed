# 0086 outbox_event 128 分片改造（P3）

> 规划中未实施部分：Canal Server / 转发器 / Topic 属于外部基建，本次仅落地应用侧代码与配置，
> 未部署中间件；存量数据迁移需按 `docs/ops/outbox-sharding-rollout.md` 在停机窗口执行。

## 背景

P0–P2 已完成：大 V 优先扫描与动态配额、作者索引分片 + L1 缓存、Outbox CDC 消费骨架。
P3 收口发件箱自身的写入热点与体积无上限问题：`outbox_event` 从 `!SINGLE` 单表改为
128 物理分片（`hashMod128`，分片键雪花 `id`）。

## 关键决策：分片键只能是 id

读码核对 10 个 SQL 查询点后确认：`insert/findById/claim/markSent/markFailed/reDeadLetter`
这 6 个主链路操作全部只有 `id` 可用（快路径 `afterCommit(eventId)`、补偿路径 `tryDeliver(eventId)`、
CDC 消费者 `tryDeliver(event.eventId())` 全链条只传 eventId）。若按 `aggregate_id` 分片，
这 6 个 CAS 操作会广播到 128 片——每投一条事件 4 次跨片 UPDATE，性能灾难。
按雪花 `id` 分片则主链路全部单分片命中，且雪花 id 天然分散、无 `user_id` 那样的热点。

扫描类（`findDueIds/reapStuck/findDeadIds/backlog`）因只有状态与时间条件，任何分片键下都广播，
这是队列表分片的固有代价，靠「先清 SENT、活跃表小」控制绝对成本。

## 改动清单

| 文件 | 改动 |
|---|---|
| `OutboxEventRepository.java` | 新增 `purgeSent(batchSize, before)`：分批删 SENT 冷行（保留窗口由调用方控制，带 LIMIT 防长事务） |
| `OutboxSentCleaner.java`（新增） | SENT 清理定时任务：只删 SENT、只删超时冷行、非终态不碰、fail-open；默认开，1h 一轮，`tf_outbox_sent_purged_total` 指标 |
| `application.yml` | 新增 `turbofeed.outbox.sent-cleaner*` 配置（retention-days=7 / batch-size=500 / 周期） |
| `shardingsphere-config.yaml` / `-128.yaml` / `shardingsphere-config-local.yml` | `outbox_event` 从 `!SINGLE` 移到 `autoTables`（分片键 id，hashMod128） |
| `deploy/mysql/outbox_event_128.sql`（新增） | 128 张物理表 DDL（偶数 ds_0 / 奇数 ds_1，列/索引与原单表逐字一致） |
| `init-local.sql` | 单表 `outbox_event` DDL 替换为内联的 128 表段落（与 media_like 等 128 表处理方式一致） |
| `docs/ops/outbox-sharding-rollout.md`（新增） | 迁移步骤、路由形状表、CDC 兼容、回滚、已知边界 |
| `OutboxSentCleanerTest.java`（新增） | 保留窗口计算、参数钳制、fail-open、零删除 4 项单测 |

## 验证

- `mvn -pl tf-gateway -am compile`：通过。
- `mvn -pl tf-gateway test`：514 tests / 0 failures / 0 errors / 8 skipped，新增 4 项全绿。
- 静态校验：三份 SS 配置各含 1 处 autoTables `outbox_event`、0 处 SINGLE 登记；
  `outbox_event_128.sql` 与 `init-local.sql` 均为 128 张物理表。
- 未真启动 / 未执行迁移：需先跑清理器、在停机窗口按 ops 文档迁移存量，再重启验证。
