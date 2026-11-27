# 事务发件箱 outbox_event 分片改造（P3）灰度与迁移说明

> 状态：代码已实施（SENT 清理器 + autoTables 配置 + 128 表 DDL），**默认配置已切到分片**，但
> 「存量数据迁移」与「Canal 订阅分片表」是外部运维动作，需按本文步骤执行。单表 DDL 仍保留在
> 历史 changelog 中，`deploy/mysql/outbox_event_128.sql` 与 `init-local.sql` 已是分片形态。

## 1. 为什么改 + 为什么分片键是 id

| 决策 | 结论 | 依据 |
|---|---|---|
| 是否分片 | 是（2 库 × 64 表 = 128） | 抖音量级写入下，单表成为写入热点与体积无上限的瓶颈 |
| 分片键 | **`id`（雪花）**，`hashMod128` | 投递主链路 `findById`/`claim`/`markSent`/`markFailed`/`reDeadLetter` 全部只有 eventId；按 `aggregate_id` 会让这 5 个 CAS 操作广播到 128 片，每投一条 4 次跨片 UPDATE |
| 前置 | **先清理 SENT 终态行** | SENT 只增不减、占体积绝对主体；不清就分片 = 广播查询代价抵消写入分散收益 |

### 分片后 SQL 路由形状

| 方法 | SQL 条件 | 路由 | 说明 |
|---|---|---|---|
| `insert` / `findById` / `claim` / `markSent` / `markFailed` / `reDeadLetter` | `WHERE id = ?` | **单分片** | 主链路，占 QPS 绝大多数 |
| `findDueIds` / `reapStuck` / `findDeadIds` | 仅 `status` + 时间条件 | 广播（128 片） | 扫描类，ShardingSphere 归并排序后取 TopN；正常空转时成本 = 128 次索引命中 |
| `backlog` | `status GROUP BY` | 广播（128 片） | 水位统计，ShardingSphere 归并聚合；15s 一次 |

> 扫描类广播是队列表分片的固有代价，无法消除，只能靠「活跃表小」来控制绝对成本。
> SENT 清理正是为此：清掉终态行后，广播扫描面对的是 128 张近乎空表。

## 2. SENT 清理器（前置开关）

- 类：`OutboxSentCleaner`，默认开启（`turbofeed.outbox.sent-cleaner-enabled=true`）。
- 语义：**只删 SENT**、只删 `created_at < now - retention-days` 的冷行；非终态（PENDING/SENDING/FAILED/DEAD）一行不碰。
- 分批：每轮每片最多 `batch-size` 条，`fixedDelay` 多轮收敛，避免长事务。
- fail-open：清理失败只 WARN + `tf_outbox_sent_purged_total` 不增，不影响投递。
- 配置项（均在 `turbofeed.outbox.sent-cleaner` 下）：

| 配置 | 默认 | 含义 |
|---|---|---|
| `retention-days` | 7 | 保留窗口，给对账/审计缓冲 |
| `batch-size` | 500 | 每轮每片删除上限 |
| `poll-interval-ms` | 3600000 | 1 小时一轮 |
| `initial-delay-ms` | 300000 | 启动 5 分钟后开始 |

**上线顺序：先单独把清理器跑起来（保持单表配置），观察 `tf_outbox_sent_purged_total` 增长、
确认 SENT 存量降到可接受水位，再进入下面的分片迁移。**

## 3. 存量数据迁移步骤（停机窗口）

切换配置会让逻辑表 `outbox_event` 指向分片表，旧单表数据必须迁走，否则「事务已提交但补偿行丢失」。
按雪花 id 的 `HASH_MOD(128)` 路由（与 ShardingSphere 一致：`Math.abs(id.hashCode()) % 128`，
偶数下标落 ds_0、奇数落 ds_1）。

1. **停写**：暂停网关实例（或置 `turbofeed.outbox.relay-enabled=false` + 停业务写入），确保无新事件行。
2. **排空**：等待快路径 + Relay 把非终态行投完，理想状态是库里只剩 SENT 与少量 DEAD。
   DEAD 行若已达重投上限、确认无需补偿，可与 SENT 一并归档删除（人工确认）。
3. **备份旧单表**：
   ```sql
   CREATE TABLE turbo_feed_1.outbox_event_backup AS SELECT * FROM turbo_feed_1.outbox_event;
   ```
4. **建分片表**：`mysql -uroot -p < deploy/mysql/outbox_event_128.sql`
5. **迁移非终态行**：按 `id` 哈希写入对应物理分片（示例伪代码，需按实际 id 计算分片号）：
   ```
   FOR each row WHERE status IN ('PENDING','SENDING','FAILED','DEAD'):
       shard = Math.abs(row.id.hashCode()) % 128
       db    = (shard % 2 == 0) ? turbo_feed_1 : turbo_feed_2
       INSERT INTO {db}.outbox_event_{shard} VALUES (row...)
   ```
   > ⚠️ 分片号必须用 Java `Long.hashCode()`（高 32 位异或低 32 位）计算，与 ShardingSphere
   > `HASH_MOD` 算法逐字一致；用 `id % 128` 会落到不同分片导致 `findById` 查不到。
6. **校验**：分片表非终态行数之和 = 迁移前行数；抽样 `SELECT ... WHERE id=?` 能命中。
7. **切换配置**：三份 SS 配置（`shardingsphere-config.yaml` / `-128.yaml` / gitignored 的
   `shardingsphere-config-local.yml`）均已把 `outbox_event` 从 `!SINGLE` 移到 `autoTables`；
   重启网关，观察启动日志无 `TableNotFoundException`。
8. **观察**：`tf_outbox_backlog{status}` 四个状态归零、`tf_outbox_relay_round_duration_seconds` 稳定、
   `tf_outbox_dead_total` 不异常增长；确认 30 分钟无异常后删除备份表。

## 4. 与 P2 CDC 的兼容

- Canal 订阅从「单表 `outbox_event`」改为「128 张物理表」；转发器按 `aggregate_id` 做 MQ key
  （保证同一业务聚合的事件有序），消息体仍只含 `eventId` + `traceId`（`OutboxCdcEvent`）。
- `OutboxCdcConsumer` 收到 eventId 后调用 `OutboxService.tryDeliver(eventId)`，该函数按 id 精准命中
  单片，与分片改造完全兼容，无需改动消费者。
- Canal / 转发器 / Topic 属外部基建，本机已部署并完成端到端验证；生产接入仍需单独授权，见 `docs/ops/outbox-cdc-rollout.md`。

## 5. 回滚

- 配置回滚：把三份 SS 配置的 `outbox_event` autoTables 段移除、在 `!SINGLE` 重新登记 `ds_0.outbox_event`。
- 数据回滚：停机，把备份表 `outbox_event_backup` 重命名回 `outbox_event`，重启网关。
- 回滚窗口内（切换后未清 SENT）新写入的分片行需反向归并回单表，按 id 范围导出插入即可。

## 6. 已知边界

- 扫描类查询广播（见 §1 表）永久存在，靠 SENT 清理控制成本；不可接受时需上 CDC 流式投递（P2）
  替代 Relay 轮询，而非退回首表。
- `backlog()` 在分片下由 ShardingSphere 归并 COUNT/MIN，语义与单表一致，指标可信。
- `purgeSent` 的 `DELETE ... LIMIT ?` 在分片下广播到各片、每片独立 LIMIT，单轮删除上限 = 128 × batch-size。

## 7. 本地真实验证记录（2026-11-27）

在本机 MySQL 3306 + ShardingSphere JDBC driver 5.5.3 + `shardingsphere-config-local.yml` 实跑：

| 验证项 | 方法 | 结果 |
|---|---|---|
| 分片表分布 | `information_schema` 精确计数 | turbo_feed_1=64、turbo_feed_2=64，共 128，偶 ds_0/奇 ds_1 ✅ |
| 迁移分片号算法 | Java `Math.abs(Long.hashCode(id)) % 128` vs `id % 128` | **必须前者**：`id=1789889422631777` 真实 shard=4，而 `id%128=97`；用后者会路由错乱 ✅ |
| `findById` 路由 | ShardingSphere JDBC 探针 + `sql-show` | id=1789889422631777 → `ds_0_read ::: outbox_event_4` 命中；id=401232572132233216 → `outbox_event_102` 命中 ✅ |
| `insert` 路由 | 插入探针行查路由 | id=1234567890123456789 → `ds_1_primary ::: outbox_event_97`（奇数落 ds_1），findById 查回 PENDING ✅ |
| `findDueIds`（参数版，与 Repository 逐字一致） | `status IN(?,?) AND next_attempt_at<=? ORDER BY id LIMIT ?` | 广播 128 片归并，命中到期行 ✅ |
| `backlog` 聚合 | `GROUP BY status` 广播 | `DEAD=2, PENDING=1`，与物理表一致 ✅ |

- 存量迁移实跑：旧单表 55 行（53 SENT + 2 DEAD），备份为 `outbox_event_backup` 后，
  2 条 DEAD 按真实 shard（4 / 102）迁入分片表并校验命中；SENT 冷行未迁移（终态、无投递价值）。
- 探针行已删除；观察期结束后备份表 `turbo_feed_1.outbox_event_backup` 已清理（复核 `information_schema` 为 0 张）。
- 上述表格是 JDBC 层直连 ShardingSphere 的路由实证。后续在 Redis cluster / MinIO / RocketMQ / Canal 全部在线的条件下，
  已用 `--spring.profiles.active=mq --spring.profiles.include=ss-local` 完成网关完整真启动，
  事件按 id 精准路由并从 INSERT 到 SENT，详见 `docs/ops/outbox-cdc-rollout.md` §7。
