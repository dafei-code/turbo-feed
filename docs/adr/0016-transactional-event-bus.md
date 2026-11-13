# ADR-0016：泛化发件箱为事务性事件总线（评论/上传/删对象/消费端统一 strict）

- **状态**：提议（Proposed，2026-11-13）
- **所属改进项**：无（基于 2026-11-13 全量代码走查的分布式事务评估）
- **决策人**：dafei
- **关联 ADR**：ADR-0003（tf-counter）、ADR-0008（时间线分层）、ADR-0012（异步执行器背压）

## 1. 背景（Context）

现有 outbox 骨架（`OutboxService` / `OutboxRelay` / `OutboxDeadLetterSweeper` / `outbox_event` 单表）设计完整：事件行与业务同事务、CAS claim、卡死回收、死信重投、快慢路径共用 `tryDeliver`。但代码走查确认：**它只承载 `TIMELINE_APPEND` / `TIMELINE_REMOVE` 两种事件**（`OutboxService.dispatch` 的 switch 仅两个分支），其余跨系统写入仍是"直发 + fail-open"，失败即永久丢失：

| 链路 | 代码位置 | 现状缺陷 |
| --- | --- | --- |
| 评论正文落库 | `RocketMqCommentEventPublisher.publish`（catch 后仅 warn）；`CommentService.publish` 中这是评论唯一的 DB 落库路径 | MQ 发送失败 → Redis 有评论、DB 永远没有，灾备重建丢数据 |
| 评论持久化消费 | `CommentPersistConsumer.onMessage` catch 后只 warn | 消息被 ack，RocketMQ 不重试不进 DLQ；与 `FeedTimelineConsumer` 的 strict 上抛语义相反，同代码库两种口径并存 |
| 上传审核事件 | `RocketMqMediaEventPublisher` 失败降级为内存 `ApplicationEventPublisher` | 降级瞬间进程崩溃 → 事件丢失 → 帖子永久卡 PENDING，无任何补偿任务扫这种帖子 |
| 删帖对象存储 | `MediaUploadService.delete()`（`@Transactional`）内先 `storageClient.delete` 物理删，再 DB 逻辑删 + outbox enqueue | 对象存储不参与本地事务：事务回滚 → 对象已没、行仍 APPROVED → 内容可见但图全裂 |
| 时间线 DLQ 重放 | `FeedTimelineDlqService.replay` 把旧事件按**重放时刻**重新发回主 topic；`FeedTimelineDeliveryService.deliver` 无任何防旧机制 | APPEND 进 DLQ → REMOVE 正常消费 → 重放 APPEND → 已删内容复活 |
| 引擎 HTTP 时间线通道 | `FeedTimelineController` 的 `/timeline/append|remove` 无 `@ConditionalOnProperty` | MQ 开启后 HTTP 通道仍活着，双通道并存破坏同 key 顺序保证 |
| outbox 退化分支 | `OutboxService.deliverAfterCommit` 无事务同步时静默直投 | 行未提交就投递的边界场景无任何日志留痕 |

**约束**：不重写状态机（现有 claim/reap/死信机制已验证）；不改对外契约（消息体新增字段必须可空，沿用 `ContractVersion` "加可空字段不升版本"规则）；不引入新中间件。

## 2. 决策（Decision）

1. **发件箱泛化为事务性事件总线**：`OutboxEventType` 从 2 种扩到 6 种——新增 `COMMENT_PERSIST`、`MEDIA_UPLOADED`、`COUNTER_INCR`、`STORAGE_DELETE`。`outbox_event` 表**零 DDL**（现有列足够，`aggregate_id` 复用为顺序键载体）。
2. **dispatch 改注册表**：`Map<OutboxEventType, OutboxDispatcher>` 替换 switch，每个 dispatcher 声明三要素：`deliver(payload)`、`severity`（判死告警等级：`COMMENT_PERSIST` 与 `TIMELINE_REMOVE` 同级 P0——用户数据丢失）、`orderKey(payload)`。
3. **评论链路**：`CommentService.publish` 补事务边界，outbox 行与发布动作同生共死；`CommentPersistConsumer` 改 strict（catch 去掉、异常上抛走 RocketMQ 重试/DLQ，maxReconsumeTimes=5）——`insertIgnore` 幂等已具备，重试安全。本地模式（`LocalCommentEventPublisher`）同样先落 outbox 行再由 dispatcher 投递，两种部署形态可靠性语义一致。
4. **上传链路**：`upload` / `MediaUploadFinalizer.finalizeAsync` 在 `batchInsert` 同事务内 `enqueue(MEDIA_UPLOADED)`；删除 `RocketMqMediaEventPublisher` 的"降级内存事件"分支（它是丢事件的元凶）。另加兜底巡检：定时扫 `status=PENDING AND created_at < now()-30min` 的帖子重新 enqueue（正常应为 0 行，是"保险费"）。
5. **删帖对象存储后移**：`delete()` 事务内只做逻辑删 + `enqueue(TIMELINE_REMOVE)` + `enqueue(STORAGE_DELETE, payload=帖内全部 mediaId)`；对象删除由 dispatcher 在 afterCommit 后执行，失败走 outbox 重试/死信。对象删除天然幂等（删不存在对象=成功）。
6. **DLQ 重放防旧**：`FeedTimelineEvent` 增加可空 `occurredAt`；`FeedTimelineDlqService.replay` 前查 timelineKey 当前状态，内容已 DELETED 且待重放为 APPEND 时跳过并计数 `tf_feed_timeline_dlq_replay_stale_total`。彻底版（二期）：REMOVE 写 24h TTL 墓碑，APPEND 到达时 `occurredAt` 早于墓碑即拒绝——不依赖重放方自觉，消费端结构防旧。
7. **通道互斥**：`FeedTimelineController` 的 `/timeline/append|remove` 加 `mq.enabled=false` 激活条件，与 MQ 消费者严格互斥。
8. **防误用与留痕**：`RocketMqFeedTimelinePublisher` 被调用时检测 `TransactionSynchronizationManager.isSynchronizationActive()`，为 true 打 WARN（不抛，避免误伤 outbox dispatch 的合法调用）；`deliverAfterCommit` 退化分支加 WARN 日志（带 eventId）。

## 3. 后果（Consequences）

**正面**
- 所有"DB 已提交、消息丢失"类静默故障（评论丢失、帖卡 PENDING、删后图裂、已删内容复活）在结构上被消灭，统一变为"可重试、可告警、可对账"；
- 消费端语义统一为 strict，新消费者照任一现有模板写都是对的；
- 零 DDL、零新中间件，全部复用已验证组件。

**负面 / 成本**
- 评论/上传主链路多一次 outbox 行写入（同事务 INSERT，约 +1ms RTT）；高 TPS 计数链路的写放大问题**不在本 ADR 解决**，由 ADR-0017 的聚合方案处理；
- dispatcher 注册表引入一层间接，新增事件类型需同时注册 dispatcher + payload 类 + 测试（用穷尽性测试兜底，替代现有 switch 的编译期穷尽检查）；
- PENDING 巡检任务与 outbox 存在重复投递窗口（靠消费端幂等消化，已确认 `MediaReviewConsumer.handleUploaded` 幂等）；
- `occurredAt` 防旧依赖事件时间戳可信，时钟回拨场景下墓碑方案（二期）才是完备解。

## 4. 备选方案（Alternatives Considered）

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 引入 Seata AT/TCC 统一分布式事务 | **否决** | 业务全部是"本地事务 + 异步事件"，无强一致跨库写；AT 的全局锁与分片设计冲突，且覆盖不了 MinIO/Redis 等非关系资源 |
| RocketMQ 事务消息替代 DB outbox | **否决（本阶段）** | 上传/评论场景需回查本地事务状态，可行但要把"事务是否提交"暴露为查询接口；且丢失 DB 侧可巡检的事件行台账。计数场景无本地事务可锚定，回查无从实现 |
| 各链路各自补 retry 组件，不动 outbox | **否决** | 四套重试逻辑必然腐化分叉——现有"快慢路径共用 tryDeliver"的正确性正是靠"补偿与正常路径同代码"保证的 |
| DLQ 重放直接调 `applyStrict` 绕过主 topic | **否决**（维持现状设计） | 绕过顺序保证，重放与在途事件交错（现有代码注释已论证，本 ADR 确认该判断仍成立，只补防旧） |

## 5. 落地记录

待实施。建议拆分两个 PR：PR-1（止损）：决策 3 的消费者 strict + 决策 7 通道互斥；PR-2：dispatcher 注册表 + 四类新事件接入 + 决策 5/6/8。验收：评论发布时杀 MQ → relay 周期内落库 0 丢失；删帖注入 outbox insert 失败 → 对象仍在；DLQ 重放已删内容的 APPEND → 被防旧拦截。
