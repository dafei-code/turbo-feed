# 全链路失败模式对账（M2 / 4.4）

> 日期：2026-10-26
> 配套：`docs/architecture/threat-model-degradation.md`（安全侧的降级矩阵）、`docs/architecture/contract-evolution.md`（契约演进）。
> 本文只做一件事：**把每条异步链路「会丢什么、会重什么、丢了怎么发现」写成显式声明**，替代「写在注释里、靠自觉执行」的现状。

---

## 1. 为什么要有这份文档

一个分布式系统的可靠性不取决于它平时表现多好，而取决于**它坏的时候行为是否可预测**。
本项目有多条异步链路（outbox 中继、RocketMQ 顺序消息、计数批量落库、行为埋点落盘），
每条都有自己的投递语义与失败窗口。这些语义此前**只活在各自类的 javadoc 里**：

- 新人不知道「内容已可见但没进时间线」这种故障是被覆盖了的、还是没覆盖的；
- 排查时不知道去哪儿找证据（哪个表、哪个日志、哪个队列）；
- 加新链路时不知道该对齐哪条口径。

把这些口径写在一张表里，是为了让「不知道」变成「查这里」。

---

## 2. 总览：六条链路的投递语义

| # | 链路 | 投递语义 | 重复后果 | 主要丢失窗口 | 发现手段 |
|---|---|---|---|---|---|
| 1 | 客户端 → 对象存储（预签名直传） | **至多一次** | 无（幂等键 = 预约 ID） | 客户端传完 bytes 不调 complete | `OrphanObjectCleanupTask` 回收 |
| 2 | complete → 落库 + 机审 | 同步事务 | 无（主键幂等） | 无（同步返回） | 接口返回码 |
| 3 | 审核通过 → 入流事件落库 | **至少一次** | 无（事件行幂等） | 事务回滚 = 业务也没成功，一致 | — |
| 4 | 入流事件 → 引擎时间线 | **至少一次** | 低（append 覆盖写 / remove 精确摘） | 重投耗尽 → DEAD；MQ 重试耗尽 → RocketMQ DLQ | `OutboxDeadLetterSweeper` + `DeadLetterAlert`；**RocketMQ DLQ 无巡检（缺口）** |
| 5 | 互动 → 计数增量落库 | **至少一次** | 无（dedup + 事务闭合，见 §4） | 进程崩溃 → 未 flush 的内存增量丢失 | `post_counter.updated_at`；**无水位指标（缺口）** |
| 6 | 行为埋点 → 兴趣画像 / 明细落盘 | **至多一次** | 无 | 队列满直接丢弃；Pod 重建丢未落盘文件 | 无（明确取舍） |

**两条不同性质的取舍**，写在这里避免后来者误判：

- 链路 4/5 选「至少一次」：丢一条 = 内容不可见 / 计数不准，是**资损**，宁可重复也要保证不丢；
- 链路 6 选「至多一次」：丢一条埋点 = 少一个训练样本，是**成本**，宁可丢也不能拖慢在线链路
  （"丢样本不丢可用性"，与 improvement-plan §2 第 9 条同源）。

---

## 3. 逐链路明细

### 3.1 链路 1：预签名直传与孤儿对象

| 项 | 说明 |
|---|---|
| 语义 | 字节落存储与元数据落库被拆成两步，属直传架构的固有成本（抖音同款链路同样需要回收机制） |
| 丢失窗口 | 客户端传完 bytes 后放弃上传 / 断网，或网关在收尾前崩溃 → 存储里留下没有 DB 行引用的对象 |
| 判定口径 | **保守优先**：只清理 `UploadReservationStore` 中「已过预约有效期且仍未被释放」的条目。正常完成或已补偿清理都会主动释放，因此「逾期仍在」= 确定没有对应帖子 |
| 对账 | `OrphanObjectCleanupTask` 周期扫描；删除失败重新入队（延后 1 分钟再试），反复失败的条目持续出现在 WARN 日志 |
| 风险取向 | 宁可晚删，绝不删一个可能还在用的对象 |

### 3.2 链路 3–4：入流（outbox + 双投递通道）

这是全系统**唯一不允许丢**的链路：内容已审核通过、对用户可见，却没进公域时间线，
用户感知是「我发了却看不到」，且没有任何报错。

| 项 | 说明 |
|---|---|
| 写入 | 事件行与业务数据在**同一事务**落库（outbox），从根上消除「数据改了、事件没发」 |
| 快路径 | 事务提交后直投（`deliverAfterCommit`）；正常情况中继轮询到的应该永远是空列表 |
| 补偿 | `OutboxRelay` 周期扫描；多实例靠 `claim` 的 CAS（PENDING/FAILED → SENDING）保证同一行只被一个实例投，**刻意不做选主**（选主会引入"主挂了谁接管"的新问题，CAS 天然无主） |
| 卡死回收 | SENDING 超过 60s 视为进程崩溃残留 → 回退 FAILED 再投 |
| 非法跃迁 | `OutboxDeadLetterSweeper` 周期扫 DEAD 行，未达重投上限的重新 open 成 PENDING（带冷却）；`TIMELINE_REMOVE`（下架）冷却更短（300s vs 3600s），因为下架比入流更急 |
| 重复后果 | **低**：引擎侧 append 按 `timelineKey` 覆盖写、remove 精确 `ZREM`，重复投递不改变终态 |
| 副作用一致性 | 由 `FeedTimelineDeliveryService` 单一入口保证「时间线 + 标签索引 + 缓存失效」三件事一起发生（M1/R7）。**通道决定传输，store 决定语义** |
| ⚠️ 缺口 | DEAD 且重投耗尽（默认 3 次）后，**只剩 `DeadLetterAlert` 打日志**。默认实现 `LogDeadLetterAlert` 只写日志，没有计数指标、没有告警通道 —— 见 §5 |

**两条通道的语义差异**（同一入口，差异只在异常是否冒泡）：

| 通道 | 异常行为 | 理由 |
|---|---|---|
| HTTP 兜底（`apply`） | 吞掉只告警，**不改动任何数据** | 网关不因引擎抖动而失败 |
| MQ 顺序（`applyStrict`） | **上抛** → RocketMQ 重试 5 次 → 耗尽进 DLQ | 吞掉异常则重试与 DLQ 永不触发，"静默丢失"只是从 HTTP 搬到 MQ |

**未知 action / 不兼容版本绝不按 REMOVE 兜底**——把一条坏消息当成一个下架指令，
是反方向的安全事故。两种通道都只告警或抛异常，不改动任何数据。

### 3.3 链路 5：计数增量落库

| 项 | 说明 |
|---|---|
| 语义 | 增量事件 → 缓冲 → 定时批量落 `post_counter`（**最终一致**）。只带增量不带绝对值，与网关实时计数（Redis 64 槽 Set）彻底解耦 |
| 幂等 | `counter_flush_dedup(batch_id, post_id)` 主键是最后一道防线；日常的不丢不重由事务保证 |
| **事务闭合（M2/4.2）** | 去重登记与批量 UPSERT 并入**同一本地事务**。改造前两步各自自动提交，留下「登记成功、UPSERT 失败 → 该批增量被永久判定为已落 → 永久丢失」的窗口，且不报错 |
| 丢失窗口 | **缓冲在内存中，进程崩溃 → 未 flush 的增量丢失**。这是明确取舍（批量换吞吐），不是缺陷；若要消除需改 Redis 共享缓冲（已支持 `redis-buffer-enabled`） |
| 重复后果 | UPSERT 是 `+VALUES()` 累加而非覆盖，因此重复批次 = 重复累加（硬伤）。这正是必须用 dedup + 事务闭合防住的原因 |
| 对账 | `post_counter.updated_at` 可看最后落库时间；dedup 表保留 1 小时（TTL 后清理） |
| ⚠️ 缺口 | **无 flush 水位指标**：批次大小、dedup 挡掉多少、flush 耗时都只在日志里 —— 见 §5 |

### 3.4 链路 6：行为埋点

| 项 | 说明 |
|---|---|
| 语义 | **至多一次**，`BehaviorLogSink` 有界队列，**满则直接丢弃计数** |
| 落盘形态 | 本地 JSONL 文件（零新中间件，最容易被任何下游吃掉的格式） |
| 丢失窗口 | ① 队列满丢弃；② **Pod 重建会丢未落盘的文件** |
| 重复后果 | 无（累加统计，但重复样本会轻微偏移画像） |
| 对账 | `target/verify/BehaviorLogVerify` 可回读校验 |
| 已知改进项 | 双写对象存储 / Kafka（improvement-plan §3 P0-4 建议 4），未实施 |

---

## 4. 降级口径汇总（安全侧降级矩阵见 threat-model-degradation.md）

| 组件 | 故障 | 降级口径 | 取向 |
|---|---|---|---|
| 引擎读（推荐流/时间线） | 不可用 | `degraded-mode=empty`（默认，生产必须）：返回空列表 + WARN，**拒绝跨分片广播** | 把单次依赖故障挡住，不引爆存储层 |
| 引擎读 | 不可用 | `local-scan`：跨分片广播回源 | **仅本地/演示，生产严禁** |
| 机审 | 异常 | **fail-closed**：强制走人审 | 宁可卡审，不放过疑似违规 |
| Token 黑名单 | Redis 不可达 | **fail-closed**：拒绝请求 | 黑名单承接"登出/风控/盗号止损"，在最需要它的故障时刻失效不可接受 |
| 内部令牌 | 未配置 | 非 `dev`/`local` profile **启动即失败** | 与 JWT secret 同等守则（M0/2.1） |
| worker-id 租约 | 续约失败 | **fail-closed**：拉闸停止生成 ID | 租约丢了还发号 = 与其它实例撞主键（M2/4.3） |
| 契约版本 | 版本高于当前 | 写路径拒绝并留痕；读路径跳过该条 | 绝不硬解（M2/4.1） |
| 行为埋点 | 队列满 | 丢弃 | 丢样本不丢可用性 |

---

## 5. 对账能力现状与缺口

方案 4.4 要求「给两个对账 job 的设计占位」。核对代码后的**真实现状**是：一个已有骨架但缺指标化，
一个完全没有。**M4 已把「缺指标化」这部分补掉**，下面区分「已落地」与「仍是缺口」，
避免后来者读旧结论或误以为全做完了。

### 5.1 DLQ 巡检告警 —— 🟢 发件箱侧已闭环 / 🔴 MQ 侧仍无巡检

| 层 | 现状 |
|---|---|
| 发件箱 DEAD 行 | ✅ `OutboxDeadLetterSweeper` 周期重投（带冷却、有上限） |
| 默认告警实现 | ✅ **M4 已升级**：`DefaultDeadLetterAlert`（原 `LogDeadLetterAlert`）= 日志 + `tf_outbox_deadletter_total{type}`。计数在写日志**之前**，日志失败也留得下指标 |
| 消费失败可观测性 | ✅ **M4 新增**：`tf_feed_timeline_consume_total{action}`（分母）+ `tf_feed_timeline_consume_failed_total{action}`（分子）——失败率曲线能提前看到「正在滑向 DLQ」 |
| **RocketMQ DLQ 深度** | 🔴 **仍无巡检**。`maxReconsumeTimes=5` 耗尽后消息进 `%DLQ%{consumerGroup}`，此后没有任何组件会去看它 |

**为什么 DLQ 深度没一起做**（不是遗漏，是依赖不满足）：读 DLQ 位点需要
`rocketmq-tools` 的 `DefaultMQAdminExt`，而它是独立 artifact——本机 `.m2` 无缓存，
且必须连到真实 name-server 才能验证。按项目纪律「先验证再宣称」，不上未验证的实现。
消费失败率指标已经把**最需要提前量的那段**覆盖住了（失败率飙升 → 5 次重试 → 才进 DLQ），
所以这块的紧迫性低于「完全没有信号」的发件箱侧。

**DLQ 巡检 runbook（运维侧，不依赖代码改动）**：

```bash
# 1) 看 DLQ 里积了多少（位点差 = 未消费条数）
mqadmin consumerProgress -n <namesrv:9876> -g turbofeed-feed-timeline-group   # 看 %DLQ% 行
# 2) 抽样看内容（会消耗消息，先确认可重投）
mqadmin queryMsgByKey -n <namesrv:9876> -t %DLQ%turbofeed-feed-timeline-group -k <timelineKey>
# 3) 修复后重投
mqadmin resetOffsetByTime -n <namesrv:9876> -g turbofeed-feed-timeline-group -t %DLQ%... -s now
```

**告警规则建议**（Prometheus）：

```yaml
- alert: FeedTimeline消费失败率过高
  expr: |
    sum(rate(tf_feed_timeline_consume_failed_total[5m]))
      / clamp_min(sum(rate(tf_feed_timeline_consume_total[5m])), 0.001) > 0.05
  for: 5m
  # 注：分母 + 分子的口径都按 action 分标签，sum 汇总后算总失败率；
  # 如需单看下架链路，把 action="REMOVE" 加进两边。

- alert: 发件箱死信产生
  expr: increase(tf_outbox_deadletter_total[10m]) > 0
  for: 1m
  # 下架类（type="TIMELINE_REMOVE"）应单独配一条更急的（P1），
  # 因为「下架没生效 = 内容仍在公域可见」是正向错误。
```

### 5.2 Outbox 积压水位 —— 🟢 已落地（M4）

| 项 | 现状 |
|---|---|
| 统计出口 | ✅ `OutboxEventRepository#backlog()`：按状态分组取行数 + 该状态最老一行创建时间 |
| 指标 | ✅ `tf_outbox_backlog{status}`（PENDING/SENDING/FAILED/DEAD）与 `tf_outbox_oldest_age_seconds{status}` |
| 刷新 | `OutboxBacklogMetrics`，默认每 15s（`turbofeed.outbox.backlog-poll-ms`） |
| 可关 | `turbofeed.outbox.backlog-metrics-enabled=false` |

两个刻意选择，改之前先读：

1. **四个状态启动即注册为 0**，不是「查到才建」。否则积压归零会表现为「时序消失」，
   而 Prometheus 里「时序消失」与「一直是 0」不是一回事（`absent()` / `rate()` 语义都会变）。
2. **查失败时保留上一轮的值**（fail-quiet），不置 0。指标停在旧值比掉到 0 更接近事实——
   掉 0 会被读成「积压已消化」，正好把最需要人看见的情况伪装成好事。

**告警规则建议**：

```yaml
- alert: 发件箱积压
  expr: sum(tf_outbox_backlog{status=~"PENDING|FAILED"}) > 500
  for: 10m
- alert: 发件箱积压过久
  expr: max(tf_outbox_oldest_age_seconds) > 600     # 最老一条卡了 10 分钟
  for: 5m
- alert: 发件箱卡死投递
  expr: tf_outbox_backlog{status="SENDING"} > 0
  for: 5m    # SENDING 正常应瞬时归零，长期非零 = 有实例投递中崩溃
```

### 5.3 其余已有能力（显式登记，避免重复造轮子）

| 能力 | 实现 |
|---|---|
| 孤儿对象回收 | `OrphanObjectCleanupTask`（保守判定 + 失败重入队） |
| 发件箱补偿 | `OutboxRelay`（CAS claim + 卡死回收） |
| 死信重投 | `OutboxDeadLetterSweeper`（区分下架/入流冷却） |
| 计数 flush 幂等 | `post_counter` + `counter_flush_dedup`（M2/4.2 已事务闭合） |
| 计数 flush 批次/去重指标 | `tf_counter_flush_batch_size` / `_duration_seconds` / `_empty_total` / `_dedup_skipped_total`（M4） |
| 契约版本协商 | `ContractVersion`（M2/4.1） |
| 链路追踪 | `TraceIds` + 网关/引擎 `TraceIdFilter` + `TracePropagationInterceptor`（M4） |

### 5.4 观测面仍未覆盖的地方（M4 之后）

| 缺口 | 影响 | 归属 |
|---|---|---|
| MQ 消费侧拿不到生产者 traceId | 消费链路日志与生产请求无法串成一条 | 需在 `FeedTimelineEvent` 加字段并升 schemaVersion |
| `tf-counter` 无入站 traceId | HTTP 兜底增量这条链路断链（九成流量走 MQ，影响小） | 随 MQ 契约一起做 |
| RocketMQ DLQ 深度 | 进 DLQ 的消息无人知晓，只能靠人工/外部巡检 | 需 name-server 环境 |
| 前端/客户端埋点未接 traceId | 用户侧报障只能给时间，不能给 id | 待前端工程化（P1-5） |

---

## 6. 排查手册：症状 → 去哪儿找证据

**第 0 步（M4 之后一律先做这个）**：拿到 traceId 再往下查。用户报障时让他提供响应头
`X-Trace-Id`，然后一条命令把整条链路拉出来：

```bash
grep "<traceId>" gateway.log engine.log          # 网关与引擎的日志一次对齐
grep "<traceId>" gateway.log | grep -E "ERROR|WARN"   # 只看异常
```

> 拿不到 id 时（老版本客户端、异步任务）才退回按时间戳对齐——那是**兜底手段，不是首选**。

| 症状 | 先查 | 再查 |
|---|---|---|
| 「我发了却看不到」 | outbox 该 `aggregate_id` 的事件行状态（PENDING/FAILED/DEAD/SENT） | 引擎侧 `tf:feed:tl:*` 是否有该 `timelineKey` 成员；推荐流缓存是否已失效 |
| 「已下架却还在展示」 | outbox `TIMELINE_REMOVE` 事件状态 | 引擎反查索引 `tf:feed:idx:` 是否对得上（`timelineKey` 口径不一致会让 `ZREM` 静默失效） |
| 「发布后一直不可见」 | `tf_outbox_backlog{status}` 是否在涨（补偿通道是否追不上） | `tf_outbox_oldest_age_seconds{status}` 最老一条卡了多久；`tf_outbox_deadletter_total` 是否在增长 |
| 「点赞数不对」 | `post_counter.updated_at`（多久没落库了） | `tf_counter_flush_batch_size` 是否有样本（调度还活着吗）；`tf_counter_flush_dedup_skipped_total` 是否非 0（flush 是否在反复重放） |
| 「开 MQ 后新内容不可见」 | `rate(tf_feed_timeline_consume_failed_total)` / `rate(…_total)` 失败率 | 是否走了 `FeedTimelineDeliveryService` 单入口（旧代码曾漏掉缓存失效）；RocketMQ DLQ 是否有堆积（§5.1 runbook） |
| 「兴趣画像不更新」 | 行为事件是否到达引擎 | `BehaviorLogSink` 队列是否长期打满（样本持续丢弃） |
| 「注册偶发失败」 | 是否 `DuplicateKeyException`（主键冲突） | 多实例 worker-id 是否重复；租约模式下看续约日志是否被拉闸 |

---

## 7. 变更这条文档的规则

新增异步链路时，**必须**同步更新本文 §2 的表格，并回答四个问题：
投递语义是什么 / 重复了会怎样 / 什么情况下会丢 / 丢了怎么发现。
回答不了第四个问题的链路，说明它还不能被安全地放进生产。
