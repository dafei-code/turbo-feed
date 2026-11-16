# 突发档（RocketMQ 削峰）启用与回滚

> 评审优化项① 的部署侧配套。本文只描述「怎么开、怎么验、怎么退」，代码实现见
> `RocketMqCommentEventPublisher` / `CommentPersistConsumer` / `RocketMqCounterEventPublisher`
> 与 `docs/adr/0016-distributed-transaction-redesign.md`（合并版 ADR 的 PR-A / PR-E）。
>
> **默认形态不变**：`turbofeed.mq.enabled=false`、prod compose 的 `SPRING_PROFILES_ACTIVE` 默认仍为
> `prod`。不设置本文任何变量时，行为与本轮改造前一致（零回归）。

## 一、什么时候该开

| 场景 | 建议 |
| --- | --- |
| 日常千级 QPS 互动 | 不开。进程内 `interactionExecutor` 异步落库 + HTTP 计数足够，少一个中间件依赖 |
| 大促 / 热搜 / 爆款单帖预期万级集中写 | 开。把评论正文与计数增量从进程内内存队列挪进 broker，缩小崩溃丢失窗口 |
| 只是想「不丢数据」 | 开 MQ **不够**。必须先落地 PR-A（严格消费）与 PR-E（flush 失败暂存），否则 broker 抖动时仍会静默丢 |

本轮已把 PR-A / PR-E 一并落地，因此突发档是「吞吐 + 可靠性」的组合，而不是单纯加中间件。

## 二、为什么"开 MQ"本身不等于可靠

改造前 MQ 分支有三处 fail-open，本轮已全部收紧：

| 位置 | 改造前 | 改造后 |
| --- | --- | --- |
| `RocketMqCommentEventPublisher` | 发送失败 WARN 后丢弃，正文永不进库 | ERROR + 本地幂等兜底落库（`insertIfAbsent`），与消费端共用幂等路径 |
| `CommentPersistConsumer` | catch 后 WARN 直接 ack；`INSERT IGNORE` 吞所有 SQL 错误 | 改「先查主键 + 普通 INSERT」；异常包装上抛，触发 RocketMQ 重试 / DLQ |
| `RocketMqCounterEventPublisher` | 发送失败 WARN 后丢弃增量 | MQ → HTTP 直投兜底 → 有界本地重试队列（默认 1 万条上限、5s 一轮、单轮 500 条预算） |

计数消费端 `CounterMqConsumer` 原本就没有 catch，异常天然上抛重试，本轮保持不变。

## 三、开关清单

| 变量 | 作用 | 默认 | 突发档 | 已转发到 |
| --- | --- | --- | --- | --- |
| `TURBOFEED_SPRING_PROFILES` | 激活 `application-mq.yml`（三处 name-server 声明） | `prod` | `prod,mq` | gateway / engine / counter |
| `TURBOFEED_MQ_ENABLED` | 装配 MQ 实现类 + 消费者 | `false` | `true` | gateway / engine / counter |
| `TURBOFEED_ROCKETMQ_NAME_SERVER` | NameServer 地址 | `127.0.0.1:9876` | 生产地址 | gateway / engine / counter |
| `TURBOFEED_COUNTER_ENABLED` | 网关计数总闸 | `false` | `true` | gateway |
| `TURBOFEED_COUNTER_BASE_URL` | 计数 HTTP 兜底目标 | `http://localhost:8081` | `http://tf-counter:8081` | gateway |

多副本相关开关（Redis 集群、共享缓冲、epoch 广播）沿用 `deploy/env/.env.multinode.example` 上半段，
突发档与多副本档可叠加。

> ⚠️ **compose `environment:` 是白名单**：未显式列出的变量进不了容器。本轮已在三个服务的
> environment 段逐条补转发；只改 `.env` 不改 compose 会重现「开关设了却静默按默认值走」的历史坑。
>
> ⚠️ **`TURBOFEED_ROCKETMQ_NAME_SERVER` 带 `TURBOFEED_` 前缀是刻意的**：它只被 yaml 的
> `${...}` 占位符读取；若写成 `ROCKETMQ_NAME_SERVER`，Spring relaxed binding 会把它当成
> `rocketmq.name-server` 属性，导致 mq profile 未激活时 starter 也创建生产者 Bean 并在启动时连不上。

## 四、启用步骤

1. **准备 broker**：至少 1 个 NameServer + 1 个 Broker，Topic 允许自动创建或预建
   `turbofeed-comment-created`、`turbofeed-counter-incr`、`turbofeed-media-uploaded`。
   prod compose **不含** RocketMQ 服务，需部署方自备；仓库根 `docker-compose.yml` 的
   `tf-rocketmq-namesrv` 仅供本地开发参考。
2. **执行共享库 DDL**：已有 MySQL 环境需手工执行
   `deploy/compose/mysql/01-counter-shared-db.sql`（initdb 只在空数据卷首次初始化时跑）。
   本轮新增 `counter_flush_pending` 表，缺表会导致失败暂存写入报错。
3. **注入变量**：把 `.env.multinode.example` 中「突发档 / 爆款单帖」整段复制到
   `deploy/env/.env.multinode`，并按需把 `TURBOFEED_ROCKETMQ_NAME_SERVER` 改成真实地址。
4. **滚动重启三个服务**：先 counter（消费者就绪），再 engine，最后 gateway（生产者）。
5. **确认装配**：日志应出现 `RocketMQTemplate` 初始化与消费者注册信息；
   网关不应再出现「计数增量发布器(HTTP)初始化」作为**唯一**计数通道
   （MQ 分支下 HTTP 只作兜底实例，不注册为 Bean）。

## 五、验证清单

| # | 检查项 | 预期 |
| --- | --- | --- |
| 1 | 发一条评论 | `comment` 表落一行；重复投递同一事件不产生第二行（消费端幂等） |
| 2 | 杀掉 counter 的 DB 连接后发评论 | 日志出现 `评论事件落库失败（上抛触发 RocketMQ 重试 / DLQ，不 ack）`，消息进入重试而非被 ack |
| 3 | 停掉 broker 后点赞 | 日志依次出现 `投递 tf-counter(MQ)失败，转 HTTP 直投兜底` → `MQ→HTTP 兜底成功`；`post_counter` 仍累加 |
| 4 | broker 与 tf-counter 同时不可用时点赞 | 出现 `计数增量进入本地重试队列`，恢复后 `计数增量本地重试成功`；队列满则 ERROR 带 postId |
| 5 | 制造 flush 落库失败 | 出现 `整批快照已存失败暂存（原 batchId 待重放）`；恢复后 `失败批次重放成功` 且 `post_counter` **不重复累加** |
| 6 | 对账 | `GET /internal/counter/reconciliation/dry-run` 返回 `pendingBatches` / `pendingPosts` / `pendingDeltas`，接口只读、不改数据 |
| 7 | 指标 | `tf_counter_flush_failed_total`、`tf_counter_flush_pending_saved_total`、`tf_counter_flush_replayed_total` 与
   `tf_counter_flush_dedup_skipped_total` 可观测 |

> 本机 Docker daemon 未运行，本轮只完成编译 / 单测 / 静态校验；
> 上表 1–7 的**真启动验证仍待有 broker 的环境执行**，请勿把静态通过当成生产验收。

## 六、回滚

把 `TURBOFEED_SPRING_PROFILES` 去掉（回落 `prod`）、`TURBOFEED_MQ_ENABLED=false`，
滚动重启三个服务即可。注意：

- 回滚只切通道，不改数据；已落 `comment` / `post_counter` 的行不受影响。
- 若回滚前 broker 中仍有未消费消息，需等消费完或人工处理，否则这些增量不会再被 HTTP 通道补发。
- `counter_flush_pending` 表保留，重放由 flush 周期自动完成，不需要随回滚清理。

## 七、已知边界（不要误读成"彻底不丢"）

| 边界 | 说明 |
| --- | --- |
| 网关本地重试队列是内存态 | 进程崩溃会丢队列内增量（上限 1 万条）。Redis 实时计数不受影响，缺的是 `post_counter` 聚合底座，需靠 B4 对账 dry-run + 重建补 |
| 评论默认档仍是 at-most-once | 未开 MQ 时 `LocalCommentEventPublisher` 的异步落库失败只 ERROR 留痕、不重试；要可靠性必须开突发档 |
| 失败暂存写不进去时无解 | 暂存与 `post_counter` 同一个库，DB 整体不可用时会走 `失败暂存写入也失败（需人工对账）` 分支，只能靠日志 + 对账 |
| 读写分离 | `shardingsphere-config-128.yaml` 已配 `!READWRITE_SPLITTING`，但真实读副本生效依赖部署侧 `TURBOFEED_REPLICA_DB_*_URL`；见优化项④ |
| 热点 Key 分槽 | 由 `TURBOFEED_LIKE_COUNT_SHARDING_ENABLED` 控制，默认关；与突发档互不依赖，见优化项③ |
