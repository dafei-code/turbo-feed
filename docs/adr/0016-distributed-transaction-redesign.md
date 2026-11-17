# ADR-0016：分布式事务重构——事务性事件总线、计数聚合底座与高并发演进（合并版）

- **状态**：提议（Proposed，2026-11-13；同日按第三方代码评审结论修订合并）
- **所属改进项**：无（基于 2026-11-13 全量代码走查 + 评审修订）
- **决策人**：dafei
- **关联 ADR**：ADR-0003（tf-counter）、ADR-0007（可观测性）、ADR-0008（时间线分层）、ADR-0011（CDC-ClickHouse，**仍为提议、零落地**，本文不引用其为既有经验）、ADR-0012（异步执行器背压）
- **修订说明**：初版（0016/0017/0018 三份）经逐条源码复核，确认 6 处按字面落地即失效的 P0 问题与 12 处 P1 问题；本合并版全部修正，并统一三份文档间的冲突口径。所有"代码事实"均附行号证据。

---

## 1. 背景（Context）

### 1.1 现状：outbox 骨架完整但覆盖面窄

现有发件箱（`OutboxService` / `OutboxRelay` / `OutboxDeadLetterSweeper` / `outbox_event` 单表落 ds_0）机制完整：事件行与业务同事务、CAS claim（`OutboxEventRepository.java:120-125`）、SENDING 卡死 60s 回收（:153-159）、死信冷却重投（:165-183）、快慢路径共用 `tryDeliver`。但**只承载 `TIMELINE_APPEND` / `TIMELINE_REMOVE` 两种事件**（`OutboxEventType.java:10-17`，`OutboxService.dispatch:144-158` 仅两分支），其余跨系统写入全是"直发 + fail-open"。

### 1.2 代码事实确认的缺陷清单（评审修订后）

**P0 级（静默丢数据 / 内容安全事故）**

| # | 缺陷 | 代码事实 |
| --- | --- | --- |
| 1 | 评论正文会永久丢失 | 默认 `mq.enabled=false` 走 `LocalCommentEventPublisher` 进程内异步落库（`CommentPublisherConfig.java:24-44` 互斥分支）；MQ 分支 `RocketMqCommentEventPublisher.java:29-37` 发送吞异常。两分支都会丢（本地：catch 吞异常 + 崩溃丢内存队列；MQ：发送失败仅 warn）。**影响不是"灾备丢数据"，而是线上读立即不一致**：评论列表全走 DB（`CommentJdbcRepository.listTopLevelFresh` 等），丢失 = 列表静默缺条 + Redis 总数与列表对不上 + 回复父评论 404，且永久 |
| 2 | 评论消费端吞异常 + INSERT IGNORE 吞所有错误 | `CommentPersistConsumer.java:32-44` catch 后 warn → ack 不重试；`CommentJdbcRepository.insertIgnore:63-68` 的 INSERT IGNORE 把超长/非空等**所有** SQL 错误降级为 warning，即使去掉 catch 重试也不会触发 |
| 3 | 上传审核事件丢失 → 帖永久卡 PENDING | `RocketMqMediaEventPublisher.java:47-61` 失败降级为**内存**事件；`upload`（`MediaUploadService.java:265`）、`finalizeAsync`（`MediaUploadFinalizer.java:106`，@Async 跨线程）、`batchInsert`（`MediaJdbcRepository.java:158`）**三处均无 @Transactional**——直接补 enqueue 会命中 `deliverAfterCommit` 的静默退化分支（`OutboxService.java:95-100`） |
| 4 | 删帖事务内先物理删对象 | `MediaUploadService.delete():737-781`：`@Transactional` 内先 `storageClient.delete` 再逻辑删 + enqueue；`MinioStorageClient.delete:211-222` catch 后仅 warn 不抛。事务回滚 → 对象已没、行仍 APPROVED → 图裂 |
| 5 | DLQ 重放破坏原始顺序 | `FeedTimelineDlqService.replay:71-93` 按**重放时刻**重发回主 topic；`FeedTimelineStore.appendStrict:119-145` 的 ZSET score = 消费时刻 `Instant.now()`；`FeedTimelineDeliveryService.deliver:91-136` 无任何防旧比较；REMOVE 是物理 ZREM 无墓碑。场景：APPEND 进 DLQ → REMOVE 正常消费 → 重放 APPEND → **已删内容复活** |
| 6 | 计数 flush 快照丢失 | `CounterFlushService.flush():89-128` 先 `drain()`（取出并清空）后 `applyBatch`，无 catch 无重投；`applyBatch` 是 `+VALUES()` 累加 UPSERT（`PostCounterRepository.java:72-76`）；`counter_flush_dedup` 是 PK `(batch_id, post_id)`、TTL 1h 的自清空表（`CounterFlushService.java:55,130-134`）。仓库注释"交由调度器下个周期用新 batch_id 重放"（:150）指向的路径**在代码中不存在** |

**P1 级**

- 引擎 HTTP 时间线通道无开关（`FeedTimelineController`），但网关侧 publisher 已互斥——真实风险面收窄为"绕过网关直连引擎 `/timeline/append|remove`"的调用（运维脚本/内网直连）。注意该类还承载 `/recommended`、`/playable`、`/interest/search`、`/behavior`（:107/121/142/161 等），**不能整类加开关**。
- `dispatch` 是 switch **语句**而非表达式，Java 17 无编译期穷尽检查（`OutboxService.java:144` 注释"漏补 case 编译报错"本身写错）——漏补分支会静默跳过 → `markSent` → 事件静默丢弃且显示成功。
- `deliverAfterCommit` 退化分支与 publisher 事务内直调，两处均无日志留痕。
- 网关→counter 通道 at-most-once：`RocketMqCounterEventPublisher` / `HttpCounterEventPublisher` 失败仅 warn；`CounterMqConsumer` 不抛异常（`CounterIncrService.accept` 纯内存缓冲）。
- `outbox_event` SENT 行无 TTL 只增不减（`OutboxBacklogMetrics.java:60` 只统计非终态）。

**评审推翻的初版错误诊断（明确删除，不再作为设计依据）**

- ~~"L1 虚高导致用户看到虚高计数"~~：用户读的是**网关 Redis 权威计数**（`MediaLikeService.likeCount:311-330` 单 Key GET + 64 槽 SCARD 兜底），与 tf-counter 的 L1 解耦（`PostCounterRepository.java:25-27` 注释明确）；L1 是 Caffeine 60s TTL（`CounterReadService.java:33`），每次成功 flush 被 DB 权威值 refresh（:121），自纠正机制已存在；`invalidate` 方法不存在。计数链路缺陷的真实作用域 = **`post_counter` 聚合底座**（灾备重建/对账用），不影响用户感知。
- ~~"`FeedTimelineEvent` 需新增 occurredAt"~~：`occurredAt` **已存在**（`FeedTimelineEvent.java:39-46` 七参 record），golden JSON 已含（`SharedContractJsonTest.java:116`）。缺的是消费端比较逻辑与可查状态源。
- ~~"`aggregate_id` 复用为顺序键"~~：DDL 口径中 `id` 才是投递顺序键（`findDueIds ORDER BY id`），`aggregate_id` 建有对账索引，不兼用。

### 1.3 约束

不重写已验证的状态机；消息体演进走 `ContractVersion`（只加可空字段不升版本；破坏性变更另立新契约类型）；不引入新中间件（否决 Seata / 全量 CDC）；高并发部分一律以实测基线为触发条件，不提前建设。

---

## 2. 决策（Decision）

### Part A：事务性事件总线（本期的核心）

**A1. 事件类型扩到 5 种**：新增 `COMMENT_PERSIST`、`MEDIA_UPLOADED`、`STORAGE_DELETE`。`outbox_event` 零 DDL；投递顺序键仍用 `id`，`aggregate_id` 维持对账用途。**计数不进 `outbox_event`**（逐事件落行与万级 TPS 量级不符，见 Part B）。

**A2. dispatch 改注册表**：`Map<OutboxEventType, OutboxDispatcher>`，各 dispatcher 声明 `deliver` / `severity`（`COMMENT_PERSIST` 与 `TIMELINE_REMOVE` 同级 P0）/ `orderKey`。注册表无编译期穷尽保护，**必须补穷尽性测试**（枚举全值逐一断言）。

**A3. 评论链路**：判重改"先查主键存在 → 存在跳过、不存在走普通 INSERT"（废弃 INSERT IGNORE，让非主键错误以异常可见）；消费端 strict 上抛走 RocketMQ 重试/DLQ；本地分支换同一幂等路径。

**A4. 上传链路（三步前置，缺一直接落地即失效）**：① `upload` / `finalizeAsync` 补事务边界（finalizeAsync 需把落库 + enqueue 收敛到一个被代理的事务方法，验证 @Async 与事务代理叠加）；② 本地模式（mq.enabled=false）的 dispatcher 语义同步定义，`LocalMediaEventPublisher` 与 `ReviewListener` 一并改造；③ 巡检**禁止扫 `status=PENDING`**（默认人工审核闸 `ReviewProperties.autoPass=false`，PENDING 是正常队列；`listPendingGlobal:378-384` 是跨分片广播查询，javadoc 明令不可生产实扫）——改为"投递水位"判据：enqueue 时同步写 per-shard 游标表，巡检只比对水位与状态差值。

**A5. 删帖对象存储后移**：事务内只做逻辑删 + enqueue(TIMELINE_REMOVE) + enqueue(STORAGE_DELETE，帖内全部 mediaId)。**前置契约修正**：`MinioStorageClient.delete` 改抛异常（保留"对象不存在视为成功"）——现状 catch-warn 会让失败被当成功 markSent。orderKey 对齐 timelineKey（postId 口径），与 REMOVE 同键保序。

**A6. DLQ 重放防旧**：① `deliver` 增加 occurredAt 比较；② **状态源一期就上墓碑**：REMOVE 写 24h TTL 墓碑 key，APPEND 到达时 occurredAt 早于墓碑即拒绝（引擎侧无 DB 依赖，"replay 前查状态"无落地路径，墓碑是唯一解，不得推二期）；判据覆盖 DELETED / TAKEN_DOWN / APPEALING 全部下架原因。

**A7. 通道互斥（范围修正）**：只对 `/timeline/append`、`/timeline/remove` 两方法（或拆出的独立类）加 `mq.enabled=false` 开关，不整类加。

**A8. 留痕**：publisher 检测事务同步激活则 WARN（不抛）；`deliverAfterCommit` 退化分支 WARN 带 eventId。

### Part B：post_counter 聚合底座落库可靠性（收窄后的计数专项）

**B1. 重新定界**：本文只解决 `post_counter` 聚合表的完整性（网关→counter at-most-once + flush 快照丢失）。**明确排除**用户感知计数（网关 Redis 权威，已解耦）。

**B2. flush 三段式交接（载体重写，评审修正版）**：
- batchId 在 **drain 之前**生成，`drain()` 返回 `Snapshot(batchId, Map<String,long[]>)`——初版"batchId 放进快照"的载体在现行 `CounterBuffer.add(postId, type, delta)` 签名下不存在，必须改接口契约；
- `applyBatch` 失败时，**整个 Snapshot 连同 batchId 存入独立失败暂存**（内存 + 持久化双层，进程崩溃可恢复），**不还回热缓冲**（还回则 batchId 必然丢失、下轮新 UUID → 累加 UPSERT 重复计数，比现状更糟）；
- 重放走独立路径，用**原 batchId** 调 `applyBatch`，命中 dedup 才是真正的幂等。

**B3. dedup 三重限制写明**：`counter_flush_dedup` 是 (batch_id, post_id) 复合键、TTL 1h 每 10min purge、且只覆盖"提交后超时"窄场景（DB 抖动事务回滚路径里 dedup 行没落，重放是干净重做）。**长周期兜底 = 对账重建，单列决策 B4**。

**B4. 对账重建（新增能力，非复用）**：admin 手动或每日 off-peak 定时；比对源 = 网关 Redis `tf:like:count:{postId}`（权威）vs `post_counter`，差额回补；**注意口径差**——post_counter 存"最终一致聚合值"、网关 Redis 是 64 槽去重 Set 计数，需先定义对齐公式再实现；现有 `AdminCounterRebuildController` 只服务网关 Redis 与 `media_like`，不碰 post_counter，本节是新建。

**B5. 消费端语义对齐**：`CounterMqConsumer` 维持"不抛异常"fail-open——写入 ADR 的 strict 豁免清单，理由：增量丢失只影响聚合底座精度，不影响用户计数（与 A3 的 strict 原则的冲突以此豁免清单收口）。

**B6. 聚合 flusher 移出本文档、单独立项**：初版的 200ms 窗口聚合（`tf:oc:agg`）涉及三重未论证前提——默认 `counter.service.enabled=false` 该链路根本不收增量；批量 payload `Map<postId,deltas>` 是**破坏性 wire 契约变更**（`CounterIncrementEvent` 四参 record 无 schemaVersion，golden 测试 `SharedContractJsonTest.java:147-161` 写死），需另立 `CounterIncrementBatchEvent` 新契约首版；初版声称"复用 ShedLockConfig"不成立（该配置在 tf-counter 且条件装配，ArchitectureTest 禁止网关依赖 tf-counter）——网关侧聚合 flusher 需自建锁 provider 与 shedlock 表。三项都需独立论证后再立项。

### Part C：高并发演进（远景，不排期）

本期不实施任何结构化改造，**只落地观测前置（PR-F）**：先加 3 个 Micrometer 指标（outbox 写入 TPS / `findDueIds` 延迟 / relay 轮内耗时）跑两周出基线，后续所有演进以实测值触发。

远景设计（修正版，供未来评审）：

- **分桶路由修正**：初版 `snowflakeId % 16` 失效——雪花低 12 位是毫秒内 sequence（`SnowflakeIdGenerator.java`：`SEQUENCE_BITS=12`、每毫秒归零），按 500 TPS 阈值算大多数事件 sequence=0，全落 bucket 0。修正为整数混淆后取高位（Fibonacci hashing：`(id * 0x9E3779B97F4A7C15L) >>> 60`），验收附"当前 TPS 下 16 桶分布直方图"。
- **合并器移到生产侧**：初版放在消费端 `deliver` 前置——ORDERLY 下同 key 本就单队列串行到达（`FeedTimelineConsumer.java:58-62`），消费端合并救不了"REMOVE 在生产者队列排队"，还破坏 strict ACK 归属。修正为网关 outbox dispatcher 投递前做同 key 合并；"队列扩容收益"（只提升不同 key 间并行度）与"同 key 合并收益"拆开论证。
- **放弃天级分区方案**：`findDueIds` 查询不含 `created_at`（`OutboxEventRepository.java:111`），RANGE 分区裁剪失效且 DROP PARTITION 会连坐删除当天 PENDING/FAILED/DEAD 行。改为"SENT 行归档任务"（`status='SENT' AND created_at < N 天` 删除/搬迁，有 `idx_dispatch` 索引可加 status 前缀）。SENT 无 TTL 只增不减的现状诊断保留。
- **容量推算改写**：16 表仍在同库同实例，受 redo log / buffer pool / IOPS 上限约束，不线性 ×16；收益限定为"锁与 B+ 树热点分散"，先实测单实例瓶颈再写上界。
- **阶段划分统一（初版 §2/§5 自相矛盾已修正）**：阶段一 = 独立 worker（无分桶）；阶段二 = 分桶 + 按桶认领（依赖路由修正）；阶段三 = 多实例多库（触发条件用 PR-F 实测值）。
- **队列数预埋 64 定性为部署变更单**：现状 topic 队列零配置（autoCreateTopicEnable=true，docker-compose 不挂 broker.conf），预埋是新增运维动作（broker.conf / mqadmin），不是代码改动。
- **全局 eventId + SETNX 幂等模板**：`FeedTimelineEvent` 现无 eventId 字段，新增字段会直接让 golden 串与 keysOf 全等断言变红（`SharedContractJsonTest.java:104-125`），落地前先改共享契约测试；SETNX TTL 与 dedup 1h 口径对齐（初版 24h 与 1h 不一致）。
- **CDC 经验表述删除**：ADR-0011 是纯设计零落地，"复用 CDC 运维经验"不成立；独立 worker 演进的依赖改为"先在当前单进程 relay 上取得 10k TPS 实测数据"。

---

## 3. 后果（Consequences）

**正面**
- 评论丢失、帖卡 PENDING、删后图裂、已删内容复活、计数聚合底座失真五类静默故障在结构上被消灭；
- 消费端语义统一（strict + 显式豁免清单），新消费者照模板写都是对的；
- 零 DDL、零新中间件；远景部分不占用本期资源，观测前置成本极小。

**负面 / 成本（必须写）**
- 评论/上传主链路多一次同事务 outbox 行写入（约 +1ms RTT）；
- PR-B 事务边界有真实风险：评论/上传流程内含 Redis 写（计数/热榜），不在 JDBC 事务内，需明确 afterCommit 语义或文档记录接受现状；
- 注册表丢失"编译期穷尽"幻觉后，穷尽性测试成为必须维护的防线；
- 评论"先查后插"丢失 INSERT IGNORE 的竞态免疫：并发同键撞主键时异常上抛走重试（语义仍正确），但重试次数会多于现状；
- 墓碑方案给引擎 Redis 增加一类 24h TTL key 空间；
- 计数失败暂存的持久层是新增组件（建议复用现有 ds_0 一张小表，不引新存储）；
- 对账重建需要先在文档中定义"64 槽去重计数 vs 裸增量聚合值"的对齐公式，否则对账本身产生误报。

## 4. 备选方案（Alternatives Considered）

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 引入 Seata AT/TCC | **否决** | 业务全部是"本地事务 + 异步事件"；AT 全局锁与分片冲突，覆盖不了 MinIO/Redis |
| RocketMQ 事务消息替代 DB outbox | **否决** | 丢失 DB 侧事件行台账；计数无本地事务可锚定（增量在 Redis 不在 DB），回查无从实现 |
| 各链路各自补 retry | **否决** | 四套重试逻辑必然腐化分叉 |
| PENDING 超时巡检（初版） | **否决** | 与默认人工审核语义冲突 + 跨分片广播查询拖垮分片 |
| INSERT IGNORE 保留 + 去 catch（初版） | **否决** | INSERT IGNORE 吞掉所有 SQL 错误，重试永不触发 |
| requeue 还回热缓冲（初版） | **否决** | batchId 丢失 → 新 UUID → 累加 UPSERT 重复计数，比丢更糟 |
| L1 失效补丁（初版 0017 决策 3） | **否决** | 诊断对象不存在（用户读网关 Redis），L1 自有 TTL + refresh 纠正 |
| 消费端合并器（初版 0018 决策 4） | **否决** | 救不了入队侧排队，破坏 strict ACK 归属；移到生产侧 |
| 天级分区 + DROP PARTITION（初版） | **否决** | 查询不含分区键、裁剪失效，DROP 连坐非终态行 |
| DLQ 重放直接调 applyStrict 绕过主 topic | **否决** | 绕过顺序保证 |
| 全量 CDC / Kafka 替代 outbox | **否决** | 运维成本远超收益；轮询在千级 TPS 下保费极低，CDC 留作远景阈值触发项 |

## 5. 落地记录

待实施。PR 序列（依赖关系严格，A→B→C→D 不可乱序；E/F 可并行）：

| PR | 内容 | 验收标准（全部可执行，不依赖文档自述） |
| --- | --- | --- |
| **PR-A** 止损 | 评论判重改"先查后普通 INSERT"、非主键错误可见；本地分支换幂等路径；publisher/退化分支双 WARN | 注入超长 content / DB 断连 → ERROR 日志可见、消息进入 RocketMQ 重试；无事务调 outbox → WARN 带 eventId |
| **PR-B** 事务边界 | `CommentService.publish` / `upload` / `finalizeAsync` 补事务边界 | 事务内注入 enqueue 失败 → 业务行回滚；finalizeAsync 的 @Async + 事务代理叠加有测试证明生效 |
| **PR-C** 契约修正 | dispatch 改 switch 表达式；`MinioStorageClient.delete` 改抛异常；STORAGE_DELETE payload + orderKey 定稿 | 新增枚举漏补 case → 编译失败；MinIO 删除失败 → outbox 行 markFailed 重试 |
| **PR-D** dispatcher | 注册表 + COMMENT_PERSIST / MEDIA_UPLOADED / STORAGE_DELETE 三类事件 + A4 水位巡检 + A6 防旧 + A7 通道收口 | 每类事件端到端：enqueue → 崩溃恢复 → relay 补投 → 消费幂等；穷尽性测试全绿；DLQ 重放已删内容 APPEND → 被墓碑拦截 |
| **PR-E** 计数底座 | B2 三段式（Snapshot 载体 + 独立失败暂存 + 原 batchId 重放）+ B4 对账重建 | flush 中途 kill 进程 → 重启后快照经暂存重放，post_counter 无重复累加（dedup 命中或 diff 校验）；对账任务 dry-run 出差异报告 |
| **PR-F** 观测前置 | outbox 写入 TPS / findDueIds 延迟 / relay 轮内耗时三指标 | 三条曲线跑两周出基线报告，作为 Part C 全部演进项的触发判据 |

**本期明确不做**：Seata / 全量 CDC；B6 聚合 flusher（独立立项论证后再议）；分桶 16 表 / 队列数预埋 / 独立 worker（等 PR-F 基线）；traceparent 解析修复前不接 OTel（遗留项，同优先级跟踪）。
