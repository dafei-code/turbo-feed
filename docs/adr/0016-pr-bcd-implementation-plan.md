# PR-B / PR-C / PR-D 实施方案（合并版 ADR-0016 的未完成部分）

- 状态：提议
- 日期：2026-11-16
- 前置：PR-A（评论落库收口）与 PR-E（计数 flush 防丢）已落地（`6651fc3` / `e78bd41`），测试全绿
- 基线文档：`0016-distributed-transaction-redesign.md`
- 本文件所有论断均以当前源码为准（附 文件:行号），不引用可能失真的文档自述

---

## 0. 现状核验结论（代码实证）

### 0.1 已经是对的（不要动）

| 事实 | 证据 |
|---|---|
| Outbox 骨架完整：同事务落事件 → afterCommit 快路径直投 → Relay 轮询补偿，快/慢路径共用同一段 `tryDeliver` | `OutboxService.java:95-147`，`OutboxRelay.java:58-81` |
| CAS claim + 卡死 SENDING 回收 + 指数退避 + DEAD 判死告警 + Sweeper 重投 | `OutboxService.tryDeliver:119-147`，`OutboxDeadLetterSweeper` |
| 无事务退化已有 WARN（PR-A 补的） | `OutboxService.java:96-105` |
| 审核/处置链路已在 `@Transactional` 内 enqueue TIMELINE_APPEND/REMOVE | `MediaReviewService.java:147,276,315,349,387,456`；`ReviewTimelineDelivery.java:128,139`；`MediaUploadService.delete:737-767` |
| payload 已携带 traceId 且补偿路径会恢复 MDC | `OutboxService.withPayloadTrace:177-189` |

### 0.2 仍然存在的缺口（本次方案的对象）

| # | 缺口 | 证据 | 后果 |
|---|---|---|---|
| G1 | `dispatch` 是 switch **语句**（不是表达式），漏分支运行时静默不投 | `OutboxService.java:150-163` | 与 `OutboxEventType` 类注释声称的"漏一处编译不过"不符；新增事件类型漏接分发 = 事件永远 PENDING 直到判死 |
| G2 | `MinioStorageClient.delete` 吞异常仅 warn | `MinioStorageClient.java:212-222` | 下架处置成功但对象残留 MinIO（孤儿对象），私有桶下无用户感知，长期泄漏存储 |
| G3 | 预签名收尾：`batchInsert`（:118）与 `eventPublisher.publish`（:129）之间无事务、无 Outbox | `MediaUploadFinalizer.finalizeAsync:106-153` | **进程在两者之间崩溃 = media 行已是 PENDING 但审核事件从未发出 → 帖子永远不审、永远不进时间线，且无任何告警**。`catch RuntimeException` 能兜住 publish 抛异常，兜不住 JVM 崩溃 |
| G4 | 直传主链路 `upload`/`complete` 同样直发事件 | `MediaUploadService.java:267,548` | 同 G3 |
| G5 | DLQ/重放无防旧：旧 APPEND 在 REMOVE 之后被重放会让已下架内容复活 | payload 无 `occurredAt` 比对，引擎侧无墓碑 | 安全性问题：已删除/下架内容重新出现在 Feed |
| G6 | 水位观测只有 backlog 计数，无"最老 PENDING 年龄" | `OutboxBacklogMetrics` | Relay 假死（线程在、不产出）时无可观测信号 |

### 0.3 决策 D1：评论不再落 Outbox（对原 PR-D 范围的收窄）

合并版 ADR 的 PR-D 原本包含 `COMMENT_PERSIST` 事件。**PR-A 落地后该子项取消**，理由：

1. PR-A 已用「严格幂等 + MQ strict 消费（重试/DLQ）+ 发送失败本地幂等兜底」闭环了评论正文可靠性；
2. Outbox 化评论 = 把异步 insert 改成"事务内同步 insert 评论行 + 同事务写事件行"——Outbox 事件行本身就是一次 DB 写，省不下这次写，反而多一行；
3. 评论表按 `media_id` 分 128 片，Outbox 表是单表（单表规则），把评论流量引进单表会制造新的写入热点。

保留形态：本地模式（mq.enabled=false）的进程内异步队列崩溃窗口是**显式接受**的取舍（上限 = 队列深度 1024，有 `gateway.async.interaction.saturated` 指标）；生产突发场景以 mq profile 为准。

---

## 1. PR-C3：分发注册表（先做，是后续所有事件类型的地基）

**问题**：switch 语句无穷尽性检查（G1）。

**方案**：`switch` 改为"枚举驱动 + 启动期校验"的注册表。

```java
public interface OutboxEventHandler {
    OutboxEventType type();                       // 处理器声明自己负责的类型
    void handle(String payloadJson) throws Exception;  // 反序列化 + 投递
}

@Component
class TimelineAppendHandler implements OutboxEventHandler { ... }
@Component
class TimelineRemoveHandler implements OutboxEventHandler { ... }
```

`OutboxService` 构造时收集所有 `OutboxEventHandler` Bean 建成 `Map<OutboxEventType, OutboxEventHandler>`，并在 `afterPropertiesSet` 校验：

```java
for (OutboxEventType t : OutboxEventType.values()) {
    if (!handlers.containsKey(t)) {
        throw new IllegalStateException("Outbox 事件类型缺少分发处理器: " + t);
    }
}
```

- **为什么是注册表而不是 switch 表达式**：switch 表达式确实也能编译期穷尽，但它把所有类型的投递逻辑堆在一个类里；注册表让新增事件 = 新增一个 Handler 类，符合开闭原则，且"枚举有值、必须有处理器"由启动期 fail-fast 保证——比运行时判死发现得早一个生命周期。
- 重复注册（两个 Handler 抢一个类型）同样启动期拒绝。
- `withPayloadTrace` 的恢复逻辑上移到 `tryDeliver` 统一做（各 Handler 不再各自处理），payload 基类或接口提供 `traceId()` 访问器。

**验收**：注释掉任一 Handler 的 `@Component` → 应用启动失败，报错信息点名缺失类型。

---

## 2. PR-B：上传链路事务边界补齐

**问题**：G3/G4——DB 落库与事件发布之间无"同生共死"屏障。

### 2.1 `MediaUploadFinalizer.finalizeAsync`（预签名收尾）

当前结构（`MediaUploadFinalizer.java:106-153`）：

```
@Async finalizeAsync:
  maybeReprocess            (存储 IO，fail-open)
  batchInsert               (media 行，PENDING)
  recordDedup / riskRoute   (fail-open 附加)
  eventPublisher.publish    (MediaUploadedEvent —— 直发，无屏障)
  idempotency.store
```

改为：

```
@Async finalizeAsync:                    // 壳不变，异常收敛/finally 释放孤儿名单不变
  maybeReprocess
  finalizeInTx(reservation, urls)        // ★ 事务方法（见下）
  idempotency.store
  recordDedup / riskRoute                // fail-open 附加留在事务外（它们失败不该回滚 media 行）

@Transactional(rollbackFor = Exception.class)
finalizeInTx:                            // ★ 新增
  mediaRepository.batchInsert(...)
  long eventId = outboxService.enqueue(MEDIA_UPLOADED, postId, userId, payload)
  outboxService.deliverAfterCommit(eventId)
```

**两个实现陷阱（必须写进代码注释）**：

1. **`@Async` 与 `@Transactional` 不能标在同一个 public 方法上指望嵌套生效**——`finalizeAsync` 走异步代理，`finalizeInTx` 必须通过**自注入（self-injection）或 `TransactionTemplate`** 调用，否则同类自调用事务代理失效，又走回"无事务退化 WARN"那条路（PR-A 加的 WARN 正是为了抓这种漏网）。
2. **`deliverAfterCommit` 必须在事务方法内部调用**（此时 `TransactionSynchronizationManager` 处于激活态）；在 `@Async` 壳里调用则必然退化——这属于接线错误，用单测锁定（mock `TransactionSynchronizationManager` 断言注册了 synchronization）。

### 2.2 `MediaUploadService.upload` / `complete`（网关收字节流 / 客户端直传确认）

同构改造：事件发布点从 `eventPublisher.publish(...)` 换成"事务内 enqueue + deliverAfterCommit"。`upload` 方法（`MediaUploadService.java:267`）目前无 `@Transactional`，**先补事务边界再接 Outbox**（评审已确认这是前置条件，顺序不可反）。

### 2.3 验收标准

- 故障注入：在 `batchInsert` 提交后、原 publish 点处 `kill -9` 网关进程 → 重启后 Relay 捡出该事件补投 → 帖子最终进入审核队列。**改造前该用例的预期结果是"帖子永远 PENDING 且无告警"，改造后必须恢复**。
- 既有单测 `MediaUploadServiceTest` / finalizer 相关测试全绿（mock 边界移到 OutboxService）。

---

## 3. PR-C1/C2：存储删除严格化 + STORAGE_DELETE 事件

**问题**：G2——`MinioStorageClient.delete:212-222` 吞异常；`ViolationDispositionService.takeDownAndPenalize:282` 内联同步调用，失败即孤儿对象。

### 3.1 客户端接口加严格语义（不破坏既有调用方）

```java
// MediaStorageClient 新增默认方法
default void deleteOrThrow(String mediaId) throws Exception {
    delete(mediaId);   // 旧实现（本地/占位）天然幂等不抛，默认桥接即可
}

// MinioStorageClient 覆盖
@Override
public void deleteOrThrow(String mediaId) throws Exception {
    try {
        client().removeObject(...);
    } catch (ErrorResponseException e) {
        if ("NoSuchKey".equals(e.errorResponse().code())) return;  // 已不存在 = 幂等成功
        throw e;
    }
}
```

- 旧 `delete`（吞异常版）保留给启动清理/孤儿回收任务用，它们本来就是 best-effort；
- **not-found 必须视为成功**：重放路径会多次删同一个对象，幂等是 Outbox at-least-once 的前提。

### 3.2 处置链路改走 Outbox

`ViolationDispositionService.takeDownAndPenalize` 中 `storageClient.delete(mediaId)`（:282）替换为：

```java
if ("minio".equals(properties.getStorage())) {
    long eventId = outboxService.enqueue(OutboxEventType.STORAGE_DELETE, mediaId, authorId,
            new StorageDeletePayload(mediaId, "minio", Instant.now(), TraceContext.current()));
    outboxService.deliverAfterCommit(eventId);
}
```

新 Handler `StorageDeleteHandler` 调 `deleteOrThrow`；失败自然进入退避重试 → DEAD → 告警 → Sweeper 重投。

**前置依赖**：确认 `takeDownAndPenalize` 的所有入口（`handleReport`、`decideReviewTask` 等）都有 `@Transactional`——`MediaReviewService` 侧已确认（:349,:387）；`ViolationDispositionService` 自身入口需在实施时逐个核对，缺则先补（这正是 PR-B 的范畴）。PR-A 加的无事务退化 WARN 会上报任何漏网调用。

### 3.3 验收标准

- MinIO 关停状态下执行下架处置：DB 状态翻转成功（用户可见的下架不受影响），STORAGE_DELETE 事件进入重试队列，指标 `outbox` 失败计数增长；
- MinIO 恢复后：Relay 自动重投成功，对象消失；
- 同一事件重放 3 次：对象删除幂等，无异常。

---

## 4. PR-D2：MEDIA_UPLOADED 接入 Outbox

**定位**：Outbox 是**可靠性层**，MQ 仍是**传输层**。Handler 内部继续走 RocketMQ（mq profile）或本地直调（默认 profile），消费侧 `MediaReviewService.handleUploaded`（`MediaReviewService.java:147`，已 `@Transactional` + CAS 幂等）**不需要改**。

```java
@Component
class MediaUploadedHandler implements OutboxEventHandler {
    public void handle(String json) throws Exception {
        MediaUploadedPayload p = objectMapper.readValue(json, MediaUploadedPayload.class);
        // mq profile: rocketMQTemplate.syncSend(topic, p.toEvent(), postId)
        // 默认 profile: mediaReviewService.handleUploaded(p.toEvent())  （本地直调，复用同事务幂等）
    }
}
```

- payload = 现有 `MediaUploadedEvent` 全字段 + `occurredAt` + `traceId`；
- **迁移策略（双跑灰度）**：`turbofeed.outbox.media-uploaded.enabled`（默认 false）。开启后上传链路只写 Outbox；观察 1 周无 PENDING 积压后，删除 `RocketMqMediaEventPublisher` 的直发路径（旧代码保留一个版本周期用于回退）。

**为什么不让 MQ 直发路径和 Outbox 并存长期运行**：两条写路径意味着两套可靠性语义，`handleUploaded` 的 CAS 幂等能兜住双投，但排障时要在两套链路里找线索，成本高于收益。灰度期例外，稳定后必须二选一。

---

## 5. PR-D3：DLQ 防旧（时间线墓碑）

**问题**：G5——`TIMELINE_APPEND` 的 DEAD 行被 Sweeper 重投时，若同一帖后续已被 `TIMELINE_REMOVE`（删除/下架），迟到的 APPEND 会让它复活。

**方案（生产端打时间戳 + 消费端墓碑比对）**：

1. `TimelineAppendPayload` / `TimelineRemovePayload` 增加 `occurredAt`（enqueue 时刻）。存量事件行无此字段 → 反序列化为 null → **放行**（向后兼容，只对新生效事件启用防旧）。
2. 引擎侧（`FeedTimelinePublisher.remove` 落地处）写墓碑：Redis `tf:timeline:tombstone:{timelineKey}` = remove 的 occurredAt，TTL 30 天。
3. `append` 落地前先查墓碑：若 `tombstone != null && append.occurredAt <= tombstone` → 拒绝投递并记指标 `feed_timeline_stale_append_blocked_total` + WARN（该事件标 SENT 不再重试，因为重试语义上永远不该赢）。
4. APPEND 之间的新旧覆盖已由引擎既有幂等保证（重复投递先摘旧位置再写新位置），不在本项范围。

**为什么不放在网关侧判断**：复活发生在引擎的时间线数据结构里，判定点必须离写最近；网关 Relay 不知道引擎当前的 REMOVE 状态。

**验收**：构造场景"APPEND 判死 → REMOVE 成功 → Sweeper 重投 APPEND" → 帖子不出现，指标 +1，事件行标 SENT。

---

## 6. PR-D4：水位巡检（可观测补全）

`OutboxBacklogMetrics` 现有 backlog 计数，补两个信号：

| 指标 | 来源 | 告警阈值建议 |
|---|---|---|
| `outbox_oldest_pending_age_seconds`（Gauge） | `SELECT MIN(created_at) FROM outbox_event WHERE status='PENDING'`，随 Relay 每轮刷新 | >60s WARN；>300s 告警（正常快路径下 PENDING 年龄应 ≈ 0） |
| `outbox_relay_round_duration_seconds`（Timer） | `relayInternal` 全程计时 | 持续 > poll-interval 说明批量/消费能力不足 |
| `outbox_dead_total`（按 type 分标签） | `markFailed` 判死处 | 任何 >0 都应有告警通道（已有 DeadLetterAlert，补指标出口） |

`oldest_pending_age` 是"Relay 假死"（调度线程活着但不再产出）唯一可区分信号——与 PR-E 给 flush 加"空周期计数"是同一个道理。

## 7. PR-D5：通道收口

新增事件类型的投递通道集中到配置，避免各 Handler 散落 `if (mqEnabled)`：

```yaml
turbofeed:
  outbox:
    channel:
      default: ${turbofeed.mq.enabled:false}   # 全局跟随
      # media_uploaded: local                  # 单类型覆盖（排障/灰度用）
```

Handler 从 `OutboxChannelResolver` 取通道决策，不在业务代码里读开关。

---

## 8. 实施顺序（每步独立可发布，回退 = 关开关）

| 序 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| 1 | PR-C3 分发注册表 | 无 | 低（纯重构，行为不变；启动校验是新增保护） |
| 2 | PR-B 事务边界（finalizer / upload / complete）+ 单测锁定自注入陷阱 | 1 | 中（事务边界变化，需回归上传链路） |
| 3 | PR-C1/C2 STORAGE_DELETE | 1（注册表）、处置入口事务核对 | 低（MinIO 故障不再阻塞处置，行为只变好） |
| 4 | PR-D2 MEDIA_UPLOADED（开关灰度） | 2 | 中（双跑期双投由 CAS 幂等兜底） |
| 5 | PR-D3 墓碑防旧 | 4 稳定后 | 低（仅拒绝本该拒绝的重放） |
| 6 | PR-D4 / PR-D5 | 无 | 低 |

**明确不做**（与原评审处置口径一致）：评论 Outbox 化（D1 已收窄）、Seata/2PC、全量 CDC、合并器。

## 9. 测试计划

| 层 | 用例 |
|---|---|
| 单测 | 注册表穷尽校验（缺 Handler 启动失败）；`deleteOrThrow` not-found 幂等；payload 无 `occurredAt` 时反序列化兼容放行；`deliverAfterCommit` 在事务内注册 synchronization（防自注入失效回归） |
| 集成 | Relay 重放 MEDIA_UPLOADED → `handleUploaded` CAS 幂等只审一次；STORAGE_DELETE 重试 → DEAD → Sweeper 重投成功 |
| 故障注入（docker compose e2e） | ① finalize 落库后 kill -9 → 重启补投 → 帖子过审；② MinIO 关停处置 → 恢复后对象被删；③ APPEND 判死 → REMOVE → 重投 APPEND → 不复活且指标 +1 |
| 回归 | 现有 `OutboxRelayTest` / `OutboxPayloadTraceTest` / `OutboxBacklogMetricsTest` / `ArchitectureTest` 全绿 |

## 10. 残留风险（显式接受）

1. 灰度期 MEDIA_UPLOADED 双路径并存，`handleUploaded` 可能收到双份——CAS 幂等兜底，但审核任务表会看到一次"重复到达"日志，属预期；
2. 墓碑存 Redis，Redis 故障时防旧失效（概率 = Redis 故障 ∧ DLQ 重放 ∧ 已删除，三者叠加）；彻底方案是引擎 DB 墓碑表，留待 PR-D3 上线后按实际命中率决定；
3. 本地模式评论崩溃窗口（D1）依然存在，量级有界（≤1024 条），靠 ERROR 日志对账。
