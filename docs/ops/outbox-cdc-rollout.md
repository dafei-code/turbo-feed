# Outbox CDC 流式投递灰度说明（P2）

> 状态：**代码全部实施，默认关闭**；Canal Server 已部署、MQ Topic 已创建、转发器已实现，
> 端到端链路（binlog→Canal→转发器→MQ→消费）已于 2026-11-27 真实验证通过。
> 生产灰度仍需按 §4 单实例开启并观察。
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
| Canal Server | 订阅 MySQL binlog，过滤 `outbox_event` INSERT | **已部署并验证**（2026-11-27，见 §6） |
| 转发器 | 把 Canal entry 转成一个 `{eventId, traceId}` 消息，分区键=`aggregate_id` | **已实现并验证**（`CanalCdcForwarder`，默认关，见 §7） |
| RocketMQ Topic | `turbofeed-outbox-cdc`（按帖分区有序） | **已创建**（8 队列 RW） |
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
| Canal Server 部署 | ✅ 已做 | 接本机 MySQL 3306 binlog（非 3307 副本，见 §6） |
| Canal → MQ 转发器 | ✅ 已做 | `CanalCdcForwarder`，按 `aggregate_id` 做分区键（见 §7） |
| Topic 创建 | ✅ 已做 | `turbofeed-outbox-cdc`（8 队列 RW） |
| compose 环境变量转发 | 未做 | 需在 gateway 段加环境变量（本节 §3，含转发器开关） |
| 端到端验证 | ✅ 已做 | JDBC/原生 MQ 探针逐段验证（见 §7），非网关完整启动 |
| CDC 消费指标 | ✅ 部分 | 转发器侧 3 个指标已埋；消费侧指标仍建议补 |

**本地通道的进程内崩溃窗口依然存在**：CDC 保证的是「事件行至少被投递一次到传输层」，
不是「审核一定完成」。LOCAL 通道下 Handler 把事件交给应用内总线即视为成功；
要端到端严格可靠需用 mq 通道（见 `docs/ops/upload-outbox-rollout.md` §5.3）。

## 六、Canal Server 部署记录（2026-11-27）

已在本机 Docker 部署 Canal Server 并验证可捕获 `outbox_event` 分片表的 INSERT：

- **编排**：`deploy/canal/docker-compose.yml`（镜像 `docker.m.daocloud.io/canal/canal-server:v1.1.7`，
  docker.io 国内不可达走 daocloud；端口 11111/11112，`host.docker.internal` 连宿主机 MySQL）。
- **实例配置**：`deploy/canal/conf/example/instance.properties`
  - 复制账号 `canal@'%'`（REPLICATION SLAVE/CLIENT + SELECT，本机 MySQL 9.3 密码策略要求强密码）。
  - 过滤正则 `turbo_feed_1\.outbox_event.*,turbo_feed_2\.outbox_event.*`，黑名单排除 `_backup`。
  - 起始位点 `binlog.000286:288147`（存量已迁移、无需回放历史）。
- **验证结果**：
  - 容器 healthy（healthcheck 用 curl 探 `/metrics`，镜像无 netstat）。
  - 实例日志：表过滤规则生效、连上 `host.docker.internal:3306`、find start position 成功、进入 binlog dump。
  - 插入一条 `outbox_event_4` 的 PENDING 行后，`curl :11112/metrics` 显示
    `canal_instance_store_produce_seq=2`、`canal_instance_put_rows=1`、`received_binlog_bytes>0`，
    证明 binlog 中该 INSERT 被解析为 entry 进入 store。探针行已删。
- **健康约束**：Canal 是拉取模型，无 client 连接时 `consume_seq=-1 / get_rows=0` 属正常——
  转发器实现前 entry 只堆积不消费；store 容量有限，长期不接转发器需监控堆积。
- **启动命令**：`docker compose -p canal -f deploy/canal/docker-compose.yml up -d`

## 七、转发器实现与端到端验证（2026-11-27）

### 转发器代码

| 文件 | 作用 |
|---|---|
| `CanalCdcForwarder.java` | `@Component` + `@ConditionalOnProperty(turbofeed.outbox.cdc-forwarder-enabled=true)`（默认关）；`@Scheduled(fixedDelay)` 每轮连 Canal 拉一批 entry，只处理 `outbox_event*` 表的 INSERT，从 after 镜像取 id/aggregate_id/payload.traceId，`syncSendOrderly(topic, OutboxCdcEvent, key=aggregateId)`，正常批 ack、MQ 失败 rollback（超 `max-rollback-rounds` 强制 ack 避免死循环，由 Relay 兜底） |
| `CanalConnectorFactory.java` | 连接器工厂（便于单测 mock），连 Canal host/port/destination |
| `CanalCdcForwarderTest.java` | 6 项单测（parseEvent 提取字段、非目标表/非 INSERT/事务 entry 跳过、坏 JSON 不影响 id） |

指标：`tf_outbox_cdc_forwarded_total`、`tf_outbox_cdc_bad_entry_total`、`tf_outbox_cdc_rollback_total`。
配置项在 `turbofeed.outbox.cdc.forwarder.*` 与 `turbofeed.outbox.cdc.canal.*`（见 application.yml）。

⚠️ **多实例约束**：Canal destination 是单订阅模型，转发器只应在一个 gateway 实例打开。

### MQ 基建（复用项目已有容器）

- name-server `rmqnamesrv` 原容器直接 `docker start` 复用；broker `rmqbroker` 因启动参数写死
  `HOST_IP=192.168.0.4`（已不可达），用相同镜像/网络/命令、仅改 `HOST_IP=192.168.0.6` 重建，
  broker 正常注册。**不是新建另一套 MQ**，是让原容器适配当前 LAN IP。
- Topic `turbofeed-outbox-cdc` 已创建（8 队列 RW）。

### 端到端验证（探针逐段，非网关完整启动）

| 环节 | 方法 | 结果 |
|---|---|---|
| MySQL INSERT → Canal | 插入带 traceId 的 `outbox_event_4` 行，查 `:11112/metrics` | `put_rows` 2→3、`produce_seq` 5→8 ✅ |
| Canal → 转发器 parseEvent | 真实 connector + `forwardInternal()` + mock RocketMQTemplate 捕获 | 捕获 `eventId=5566778899001122333, traceId=e2e-cdc-001, key=canal/e2e/post-1` ✅ |
| 转发器 → MQ 序列化 | ObjectMapper 序列化 `OutboxCdcEvent` | `{"eventId":...,"traceId":...}`，可反序列化回 record ✅ |
| MQ 发送 | `syncSendOrderly(topic, 消息, key)` | `SEND_OK`，queue=7（按 key 哈希）✅ |
| MQ → 消费 | 原生 PullConsumer 从 queue=7 拉 | 读到 `{"eventId":5566778899001122333,"traceId":"e2e-cdc-001"}` ✅ |

- 全量单测：520 tests / 0 failures / 0 errors / 8 skipped（新增 6 项转发器单测）。
- 探针行已删；Topic 内的验证消息保留（本地验证 topic，无业务影响）。
- ⚠️ 未做网关完整真启动（本机 Redis/MinIO 未起）；CDC 消费侧 `OutboxCdcConsumer → tryDeliver` 由单测覆盖，
  灰度时需按 §4 真启动验证事件行变 SENT。
