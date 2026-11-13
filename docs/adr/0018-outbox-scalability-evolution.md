# ADR-0018：发件箱高并发演进——事件分级、分桶、独立投递层与 CDC 逃生门

- **状态**：提议（Proposed，2026-11-13）
- **所属改进项**：无（ADR-0016/0017 的容量与扩展性续篇）
- **决策人**：dafei
- **关联 ADR**：ADR-0011（CDC-ClickHouse）、ADR-0012（异步执行器背压）、ADR-0016、ADR-0017

## 1. 背景（Context）

ADR-0016/0017 解决了"丢不丢"的问题，本 ADR 解决"扛不扛得住"的问题。基于代码事实的瓶颈：

| 瓶颈 | 代码位置 | 高并发表现 |
| --- | --- | --- |
| 单表写热点 | `outbox_event` 单表落 ds_0（`OutboxEventRepository` 类注释） | 事件全量 INSERT 集中一张表，B+ 树右侧膨胀成全站天花板 |
| 轮询扫描放大 | `OutboxRelay.relay()` 每 2s × 每实例一次 `findDueIds` | N 实例 = N 倍扫表 QPS，且正常应为空——纯保费随实例数线性涨 |
| 串行投递 | `OutboxRelay` 轮内 `for` 循环逐个 `tryDeliver` | 单实例补偿吞吐 ≈ 1000ms/投递RTT，下游抖动时积压追不平 |
| 快路径占请求线程 | `OutboxService.deliverAfterCommit` 同步 `tryDeliver`（含 MQ syncSend RTT） | MQ 抖动 1s → 所有带事件请求线程多挂 1s，Tomcat 池被拖垮 |
| 顺序消费天花板 | `FeedTimelineConsumer` ORDERLY + 同 timelineKey 串行 | 爆款单帖事件风暴无法并行，单队列约数千 TPS 封顶，REMOVE 被 APPEND 积压阻塞（内容安全动作被延迟） |

容量输入（事件分级）：P0（评论正文/时间线/上传审核/删对象，百~千级 TPS，不可丢）；P1（计数增量，万级 TPS，最终一致）；P2（行为样本/通知，5万+ TPS，可容忍丢失）。

**约束**：不引入需要新运维体系的重型框架；每一阶段独立可回滚；演进触发条件量化，避免过度设计。

## 2. 决策（Decision）

1. **事件三级通道**：P0 走分片 DB Outbox（本 ADR 演进对象）；P1 走 ADR-0017 聚合 Outbox；P2 直发 MQ + 丢弃指标 + 日级对账（偏差 <0.1% 为健康）。可靠性预算按等级分配，禁止"所有事件同一套重机制"。
2. **存储层分桶 + 分区 + 归档**：`outbox_event` 拆 16 桶（`outbox_event_00..15`），路由 `bucket = snowflakeId % 16`（雪花 ID 低位均匀；不按 user_id 分——outbox 是系统表，避免跨片查询）；每桶按 `created_at` 天级 RANGE 分区；SENT 行每晚归档删分区（毫秒级 `DROP PARTITION`）。现状 SENT 行无 TTL，事件量起来后占表体积 99%。
3. **投递层两阶段**：
   - **阶段一（事件 >100 TPS 即启动）**：relay 从 tf-gateway 剥离为独立 worker（同 jar 不同 profile），按桶静态认领（去掉多实例 CAS 争抢）；轮内流水线——批量 claim（`UPDATE ... WHERE id IN`）→ 线程池并发投递（按 orderKey 分桶保同 key 串行）→ 批量 markSent/markFailed；快路径 `deliverAfterCommit` 的投递移出请求线程（受控线程池），修掉"MQ 抖动拖垮 Tomcat"。
   - **阶段二（事件 >5k TPS 才启动）**：CDC（Debezium/Canal 监听桶表 binlog）替代轮询，投递延迟 2s→亚秒，DB 查询 QPS 归零；应用内 relay 降级为兜底。**不到阈值不做**——千级 TPS 下轮询保费极低，且 ADR-0011 已有 CDC 运维经验可复用。
4. **顺序性扩展**：topic 队列数按"未来 2 年消费者实例上限"预埋 64（队列只扩不缩）；`FeedTimelineDeliveryService.deliver` 前置 key 级合并器——同 timelineKey 在 50ms 窗口内连续 APPEND 只执行最后一次（ZSET 覆盖写允许），REMOVE 立即冲刷窗口优先执行（内容安全动作永不被 APPEND 积压阻塞）；配合 ADR-0016 的 occurredAt/墓碑防旧，合并与重放都安全。
5. **幂等基础设施化**：所有事件携带全局 `eventId`（outbox 行 id / 聚合窗口 id）；tf-shared 提供 `IdempotentConsumer` 模板（消费前 `SETNX idem:{consumerGroup}:{eventId}` TTL 24h，Redis 不可用时降级 DB dedup 表）；`CommentPersistConsumer` / `MediaReviewConsumer` / `CounterMqConsumer` 全部挂模板。注意 tf-shared 的 ArchitectureTest 禁非 JDK 依赖——模板放 tf-shared 的纯逻辑层（键规范、判定逻辑），Redis/DB 调用留各服务（与 `TraceIds` 同构的拆分方式，见 ADR-0007 先例）。
6. **弹性降级**：outbox 积压最老行 >5min → relay worker 水平扩容（HPA on `outbox_oldest_pending_age`），同时 P1/P2 enqueue 降级为直发（牺牲可靠性保 P0 写入带宽）；下游引擎过载 → 投递层按 dispatcher 类型令牌桶降速，REMOVE 类永不限流。

## 3. 后果（Consequences）

**正面**
- outbox 写入上限从单表约 5k TPS 提到理论 8 万；投递延迟与吞吐解耦（轮询→流水线→CDC 阶梯）；
- 爆款单帖事件风暴下 REMOVE 不再被阻塞，APPEND 合并后吞吐提升一个数量级；
- 每一阶段有量化触发条件与回滚路径（分桶是表级新增，旧表投空归档；CDC 与轮询可双跑验证）；
- P2 通道把可靠性成本降到接近零，资源让给 P0。

**负面 / 成本**
- 16 桶 × 天级分区的表数量膨胀（16 × 保留天数），DDL 运维与备份策略需同步更新；
- 独立 relay worker 新增一个部署单元（监控、告警、发版都要管）；
- key 级合并器引入 50ms 可见性延迟，且合并逻辑本身成为新的正确性关键点（必须有"REMOVE 不被合并"的穷尽测试）；
- 幂等模板的 SETNX 在 Redis 抖动时降级 DB 查询，极端场景（Redis+DB 同抖）下幂等退化为"重复投递靠业务自身消化"——接受，现有各消费端业务幂等已具备；
- CDC 阶段引入 Debezium/Canal 运维成本，刻意推迟到 >5k TPS。

## 4. 备选方案（Alternatives Considered)

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 直接上 Kafka + 事务 producer 替代 outbox | **否决** | 引入新 MQ 体系的运维成本远超收益；RocketMQ 顺序消息已在用，outbox 与 MQ 职责不同（事务锚定 vs 传输） |
| 全量事件都走 CDC（跳过轮询阶段） | **否决** | 当前事件量级下轮询足够；CDC 的"状态机语义外移"（FAILED 退避要在消费侧重实现）是真实复杂度，不该提前付 |
| outbox 按 user_id 接入现有分片 | **否决** | 事件按聚合根（postId/mediaId/timelineKey）路由，与 user_id 分片正交；接入会引入跨片 relay 扫描 |
| 热点 key 拆子键（key#0..N）并行 | **暂缓** | 破坏同帖严格顺序，只有 4.3 合并器仍不够时才考虑；届时 REMOVE 需广播全部子键 |
| 消费端幂等下沉到 RocketMQ 客户端拦截器 | **否决** | rocketmq-spring 无统一拦截点，模板方法比在框架缝隙里埋钩子更可测 |

## 5. 落地记录

待实施，按触发条件分四阶段：阶段 0（ADR-0016/0017，立即）→ 阶段 1（流水线 + 快路径异步化，>100 TPS）→ 阶段 2（分桶 + 独立 worker，>500 TPS 或表 >5kw 行）→ 阶段 3（聚合 outbox + key 合并，>5k TPS 或出现热点帖）→ 阶段 4（CDC + 动态降级，>5k TPS 持续）。验收：注入 5k TPS 计数事件 outbox 写入 <50 TPS；MQ 抖动 30s 请求线程 p99 不受影响；单 key 万级风暴下 REMOVE 端到端 <3s；relay 杀 50% 实例自动接管追平。
