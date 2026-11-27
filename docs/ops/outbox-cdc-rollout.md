# Outbox CDC 流式投递灰度说明（P2）

> 状态：**代码已实施，默认关闭**；Canal / 转发器 / MQ Topic 属于外部基建，**尚未部署**。
> 关联：`docs/adr/0011-cdc-clickhouse.md`（CDC 选型 Canal，当前仍是 Proposed）、
> `docs/ops/outbox-watermark-metrics.md`（可观测口径）、
> `docs/ops/upload-outbox-rollout.md`（发件箱通道灰度）。

---

## 一、解决什么

发件箱现状是「事务落行 + afterCommit 直投快路径 + OutboxRelay 每 2s 轮询补偿」。
轮询补偿的代价随规模线性增长（扫描、逐条 DB IO），且延迟下界是轮询周期。
CDC 把「发现新事件」从**轮询 DB**换成**订阅 binlog**，延迟降到毫秒级、无扫描成本。

**保留的是语义，替换的是发现机制**：
- 语义不变：本地事务原子 + 至少一次投递 + 消费端幂等；
- 发现机制：从 `findDueIds` 轮询 → Canal 订阅 `outbox_event` 的 INSERT。

## 二、拓扑与组件（均未部署，需单独授权创建）

```
MySQL binlog (3307 只读副本)
    → Canal Server（instance 过滤 outbox_event 的 INSERT）
        → 转发器（按 aggregate_id 做消息 key）
            → RocketMQ Topic: turbofeed-outbox-cdc
                → gateway: OutboxCdcConsumer
                    → OutboxService.tryDeliver(eventId)
                        → claim CAS → Handler → markSent
```

| 组件 | 作用 | 状态 |
| --- | --- | --- |
| Canal Server | 订阅只读副本 binlog，过滤 `outbox_event` INSERT | 未部署 |
| 转发器 | 把 Canal entry 转成一个 `{eventId, traceId}` 消息，分区键=`aggregate_id` | 未实现（见 §5） |
| RocketMQ Topic | `turbofeed-outbox-cdc`（按帖分区有序） | 未创建 |
| `OutboxCdcConsumer` | 网关侧消费者，只取 eventId 交给 `tryDeliver` | 已实现，默认关闭 |

**为什么消息体只有 eventId**：payload 的唯一事实源是 DB 行（含 occurredAt 防旧、traceId）。
只传 id 则每次投递回查 DB 拿最新快照，避免「MQ 里旧版本、DB 里已更新」的分叉。

**幂等靠 claim，不靠消费去重**：`OutboxService.tryDeliver` 内 `claim` 用
CAS（PENDING/FAILED → SENDING）保证同一行只被一个投递者领走。因此快路径、Relay、CDC
三者可同时开，重复到达的消息只有一个领到，其余 CAS 落空直接返回。

## 三、开关清单

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `TURBOFEED_OUTBOX_CDC_CONSUMER_ENABLED` | `false` | 打开后网关注册 CDC 消费者 |
| `TURBOFEED_OUTBOX_CDC_TOPIC` | `turbofeed-outbox-cdc` | CDC Topic |
| `TURBOFEED_OUTBOX_CDC_CONSUMER_GROUP` | `turbofeed-outbox-cdc-group` | 消费组 |
| `TURBOFEED_MQ_ENABLED` | `false` | 前置：CDC 消费者依赖 MQ 基础设施（name-server） |

⚠️ compose `environment:` 是白名单——只改 `.env` 不转发等于没设，需在 compose 文件显式转发。

## 四、灰度步骤

1. **前置**：`turbofeed.mq.enabled=true` + name-server 可达；`outbox_event` 表已建；Relay 在跑。
2. **基建**：部署 Canal（接 3307 只读副本）+ 转发器 + 创建 Topic。这一步需单独授权创建容器/资源。
3. **单实例灰度**：只给一个 gateway 实例设 `cdc-consumer-enabled=true`，其余保持 false。
   - 验证：上传一帖 → `outbox_event` 行很快变 `SENT`；CDC 消费指标正常；
   - `tf_outbox_backlog{status="PENDING"}` 保持 ≈0；无重复投递（消费侧幂等日志）。
4. **调大 Relay 周期**：CDC 稳定后把 `poll-interval-ms` 从 2000 调到 30000，
   Relay 退化为纯兜底（**不要关掉**——Canal 挂掉时它是唯一补偿）。
5. **全量**：观察一周 `tf_outbox_oldest_age_seconds` 不增长且 `tf_outbox_dead_total` 无异常后全实例打开。
6. **回滚**：开关改回 false 并重启。已落事件行不受影响（Relay 继续扫），CDC 重复投递由 claim CAS 消化。

## 五、待办与已知边界

| 项 | 状态 | 说明 |
| --- | --- | --- |
| Canal Server 部署 | 未做 | 需授权创建容器；接 3307 只读副本，不压主库 |
| Canal → MQ 转发器 | 未做 | 可用独立小服务或 Kafka Connect；按 `aggregate_id` 做分区键 |
| Topic 创建 | 未做 | `turbofeed-outbox-cdc` |
| compose 环境变量转发 | 未做 | 需在 gateway 段加 4 个变量（本节 §3） |
| 端到端真启动验证 | 未做 | 本机无 name-server / Canal，无法验证消费链路 |
| CDC 消费指标 | 未做 | 建议补 `tf_outbox_cdc_consume_total/failed_total`（与 Relay 指标族对称） |

**本地通道的进程内崩溃窗口依然存在**：CDC 保证的是「事件行至少被投递一次到传输层」，
不是「审核一定完成」。LOCAL 通道下 Handler 把事件交给应用内总线即视为成功；
要端到端严格可靠需用 mq 通道（见 `docs/ops/upload-outbox-rollout.md` §5.3）。
