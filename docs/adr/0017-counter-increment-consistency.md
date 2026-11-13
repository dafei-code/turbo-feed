# ADR-0017：计数链路一致性 ——flush 重投、batchId 随快照、L1 失效与聚合 Outbox



* **状态**：提议（Proposed，2026-11-13）

* **所属改进项**：无（基于 2026-11-13 代码走查；ADR-0016 的计数链路专项）

* **决策人**：dafei

* **关联 ADR**：ADR-0003（tf-counter）、ADR-0016（事务性事件总线）、ADR-0018（高并发演进）

## 1. 背景（Context）

计数链路（点赞 / 评论 / 分享）代码走查发现三处相互咬合的一致性缺陷：



1. **drain 先行、失败不重投**：`CounterFlushService.flush()` 先 `buffer.drain()`（取出**并清空**，见 `CounterBuffer` 接口契约 "原子交接"），后 `repo.applyBatch(...)`；`applyBatch` 抛异常时整个方法没有 catch，快照就此消失。`counter_flush_dedup` 幂等表防的是 "整批重放"，但**代码里不存在任何自动重放路径**—— 防护对象不会发生，真正会发生的丢失（DB 抖动期间所有增量）没人管。

2. **L1 虚高**：`CounterIncrService.accept` 在接收时就对 L1 乐观加（`readService.applyLiveDelta`），flush 失败后这些增量永远不会到达 DB，读者看到虚高计数，且重启前无纠正机制。

3. **网关→counter 通道 at-most-once**：`RocketMqCounterEventPublisher` / `HttpCounterEventPublisher` 失败均只 warn，计数永久少记；`CounterMqConsumer` 消费失败无重试语义（`CounterIncrService.accept` 不抛异常，消息必然被 ack）。

**约束**：计数允许秒级延迟与最终一致，不允许 "永久少记 / 虚高"；Redis 实时计数是用户感知的权威，DB 落库用于灾备重建与对账；高 TPS（峰值万级）下不能逐事件落 `outbox_event` 行（见 ADR-0018 容量模型）。

## 2. 决策（Decision）



1. **flush 三段式交接**：`applyBatch` 成功走现状流程；抛异常时 **requeue**—— 把 snapshot 还回缓冲（内存实现 ConcurrentHashMap 合并；Redis 实现用 Lua `HINCRBY` 批量原子还回），并计数 `tf_counter_flush_requeued_total`。下一轮 flush 自动带上重试。

2. **batchId 随快照走**：batchId 放进快照结构而非每轮新 UUID。requeue 后重放沿用同一 batchId——"落库成功但响应超时" 导致的重放被 `counter_flush_dedup` 拦下，现有幂等表从此真正生效（此前它防的路径在代码里不存在）。

3. **L1 失效兜底**：requeue 路径上对涉及 postId 调 `readService.invalidate/refresh`（回读 DB 权威值），终止虚高窗口；`dedup_skipped` 指标维持 "正常值为 0" 的语义不变。

4. **接入通道分级**：`COUNTER_INCR` 纳入 ADR-0016 的事件总线，但**不逐事件落行**—— 网关侧增量先入 Redis 聚合（`HINCRBY tf:oc:agg {postId}:{type} delta`），聚合 flusher 每 200ms 把窗口快照写成**一条** outbox 行（payload 为 `Map<postId, deltas>`），行数从万级 /s 压到个位数 /s。

5. **窗口幂等键**：聚合事件幂等键 = `{instanceId}-{windowStartMs}`，消费端（`CounterIncrService` 改造接受批量事件）落 `counter_flush_dedup` 同构的 dedup 表去重。窗口作为整体重放，天然解决 at-least-once 重复加问题。

6. **消费端语义**：`CounterMqConsumer` 保持 "不抛异常" 的接收语义（内存缓冲不会失败），可靠性上移到聚合 outbox + 窗口幂等键，消费者本身无需重试。

## 3. 后果（Consequences）

**正面**



* DB 抖动期间增量 0 丢失（requeue），恢复后自动追平；

* L1 虚高窗口从 "直到重启" 缩到 "一次 flush 周期"；

* 网关→counter 从 at-most-once 升为 at-least-once + 幂等，且不引入逐事件 DB 写放大；

* 现有 `counter_flush_dedup`、Redis 缓冲、ShedLock 选主全部复用，无新组件。

**负面 / 成本**



* requeue 与并发 `accept` 存在交错窗口（还回的增量与新到增量合并），语义上无害（都是未落库增量），但 `PendingStats` 面板读数会瞬时偏高 —— 可接受，不单独处理；

* batchId 随快照需要改动 drain 的数据结构（`Map<String, long[]>` → 带 batchId 的快照对象），`CounterBuffer` 接口契约随之变化，standalone / Redis 两个实现都要改；

* 聚合窗口引入 200ms 额外延迟，且聚合器实例宕机丢失一个窗口的 Redis 增量（窗口幂等键防重不防丢）—— 接受该残差风险：Redis AOF + 窗口周期短，损失上限 200ms 的计数，对账任务可回补；

* 聚合 flusher 是新的定时组件，需纳入 ShedLock 选主（复用 `ShedLockConfig`）。

## 4. 备选方案（Alternatives Considered）



| 方案                        | 结论     | 理由                                                                 |
| ------------------------- | ------ | ------------------------------------------------------------------ |
| drain 失败时简单重试 N 次再丢       | **否决** | DB 故障通常持续秒级以上，固定次数重试只是延迟丢失；requeue 是无限重试预算且不占线程                    |
| 每轮新 batchId + 失败重试        | **否决** | 重试产生新 batchId 会使 dedup 表失效，"落库成功但超时" 场景重复加                         |
| RocketMQ 事务消息保证网关→counter | **否决** | 计数无本地事务可锚定（增量在 Redis 不在 DB），事务回查接口无从实现                             |
| L1 不乐观加，全部等 flush 后刷新     | **否决** | 牺牲 "同实例读立即可见"，点赞后计数不变的体验回退化；选择保留乐观加 + 失败失效                         |
| 消费端接 RocketMQ 重试 / DLQ    | **否决** | `CounterIncrService.accept` 是纯内存操作无失败语义，重试无对象；可靠性归属上游聚合 outbox 更清晰 |

## 5. 落地记录

待实施。验收：flush 期间断 DB 60s → 恢复后计数 0 丢失、`tf_counter_flush_requeued_total > 0`、L1 与 DB 一致；kill 聚合器实例 → 损失 ≤ 一个窗口且对账任务可识别回补。