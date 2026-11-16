# 上传链路 Outbox 灰度（PR-B / PR-D2 / PR-D5）运维说明

> 状态：**代码已实施，默认关闭**。本文只描述运行期开关与验证步骤。
> 关联：`docs/adr/0016-pr-bcd-implementation-plan.md`（提议稿，含已被本轮事实核查修正的描述）、
> `docs/adr/0016-distributed-transaction-redesign.md`（合并版基线）。

---

## 一、解决的是什么

改造前上传链路（网关收字节流 `MediaUploadService#upload`、预签名收尾
`MediaUploadFinalizer#finalizeAsync`）的形状是：

```
整帖 batchInsert（无事务） → 若干 fail-open 附加步骤 → eventPublisher.publish（直发）
```

`batchInsert` 与 `publish` 之间没有事务、没有事件行。进程若在两者之间崩溃，
media 行已是 `PENDING`，但审核事件从未发出 → **帖子永远不审、永远不进时间线，且无任何告警**。
`catch RuntimeException` 能兜住 `publish` 抛异常，兜不住 JVM 崩溃。

改造后（开关打开）：

```
TransactionTemplate 事务 {
    mediaRepository.batchInsert(rows)
    outboxService.enqueue(MEDIA_UPLOADED, ..., MediaUploadedEvent)
    outboxService.deliverAfterCommit(eventId)   // 必须在事务内调用
}
→ 提交后 afterCommit 快路径直投 → 失败留 PENDING 行，由 OutboxRelay 补偿重投
```

---

## 二、两个实现陷阱与本次的修正

| 陷阱 | 本轮做法 |
|---|---|
| `@Async` 壳上标 `@Transactional`、同类自调用 `finalizeInTx()` 会绕过事务代理 | 抽独立 Bean `MediaUploadTransaction`，内部用 `TransactionTemplate`；两个上传入口都只调它，不动外层壳 |
| `deliverAfterCommit` 放到外层壳调用会退化成「无事务立即投递」 | 放在事务回调内；单测 `MediaUploadTransactionTest` 断言调用时 `TransactionSynchronizationManager` 处于激活态 |
| 不给 `upload` 整体加 `@Transactional` | `upload` 内含对象存储 IO、`cleanupPartialPost` 手动补偿、`finally` 释放占位锁；整包事务会变长事务且与手动补偿冲突。只把两段 DB 写收进事务 |
| **重新评审漏查的真实无事务入口** | `ReviewEscalationService#onPoolPromoted`（引擎晋级回调）缺 `@Transactional`，却经 `applyDisposition → removeFromTimeline` 调 `deliverAfterCommit` → 一直打 PR-A 的无事务 WARN。本轮已补 `@Transactional(rollbackFor = Exception.class)` |
| **幽灵事件（本轮新发现）** | 旧代码把 `publish` 放在 `try/catch` **之外**，是刻意保证「只有前面全部成功才发事件」。事件挪进事务后，若事务提交后 try 内还有步骤抛异常，`cleanup` 会删 media 行、而 afterCommit 已把事件发出 → 审核侧兜底落库会「复活」已作废的帖子。**两个入口都把事务收口放在 try 内最后一组 DB 写**，其后只允许日志与 fail-open 旁路写入 |

最后一条的配套改动：`MediaUploadFinalizer` 的 `idempotency.store` 改为 fail-open
（旁路缓存写失败不该让整帖作废，更不能触发 cleanup）。这是本轮唯一一处主动的异常语义变化。

---

## 三、开关清单

| 变量 | 默认 | 含义 |
|---|---|---|
| `TURBOFEED_OUTBOX_MEDIA_UPLOADED_ENABLED` | `false` | 打开后上传链路只写 Outbox，不再直发 `MediaUploadedEvent` |
| `TURBOFEED_OUTBOX_CHANNEL_DEFAULT` | 空 | 空 = 跟随 `TURBOFEED_MQ_ENABLED`（false→local，true→mq）；显式 `local` / `mq` 覆盖全局 |
| `TURBOFEED_OUTBOX_CHANNEL_MEDIA_UPLOADED` | 空 | 单类型覆盖，只影响 `MEDIA_UPLOADED`（灰度 / 排障用） |

`docker-compose.prod.yml` 的 gateway 段已逐条转发这三个变量（compose `environment:` 是白名单，
只改 `.env` 不转发等于没设）。非法通道值由 `OutboxChannelResolver` 在启动期 fail-fast，
**不会**静默回退到 LOCAL。

属性键登记在 `tf-shared/FeatureFlagKeys`，`FeatureFlagGuardTest` 有常量值断言。

### 通道边界

- `TIMELINE_APPEND` / `TIMELINE_REMOVE`：由 `FeedTimelinePublisher` 端口按总闸选 HTTP / MQ 实现，
  不需要再问通道解析器。
- `STORAGE_DELETE`：只有本地对象存储 IO，固定本地执行。
- `MEDIA_UPLOADED`：`LOCAL` → Spring 应用内事件（`ReviewListener` 异步消费，与默认
  `LocalMediaEventPublisher` 同路径）；`MQ` → 与原 `RocketMqMediaEventPublisher` 相同的
  topic / 消息体。消费侧 `MediaReviewService#handleUploaded` 的 CAS 幂等**未改动**。

---

## 四、启用 / 验证 / 回滚

1. **前置**：`outbox_event` 单表已建、`OutboxRelay` 在跑
   （`turbofeed.outbox.relay-enabled=true`），`tf_outbox_backlog` 无持续增长。
2. **灰度**：先在单个 gateway 实例设 `TURBOFEED_OUTBOX_MEDIA_UPLOADED_ENABLED=true`，
   其余实例保持 false。此时两条路径并存但**同一帖只会走其中一条**（由命中实例的开关决定），
   不会双投。
3. **验证**：
   - 上传一帖 → `outbox_event` 出现 `MEDIA_UPLOADED` 行且很快变 `SENT`；
   - `tf_outbox_backlog{status="PENDING"}` 保持 ≈ 0，
     `tf_outbox_oldest_age_seconds{status="PENDING"}` 不持续增长；
   - 帖子最终过审、出现在推荐流；
   - 故障注入：在 `batchInsert` 提交后 `kill -9` 网关 → 重启后 Relay 捡出该行补投 →
     帖子最终进审核队列。改造前该用例的预期是「永远 PENDING 且无告警」。
4. **全量**：观察一周无 PENDING 积压后全实例打开，再考虑删除旧直发路径
   （旧代码保留一个版本周期用于回退）。
5. **回滚**：开关改回 `false` 并重启。已落的事件行不影响旧路径（旧路径不读它）；
   已由 Outbox 投递的事件由消费侧 CAS 幂等消化。回滚只切通道，不改数据。

---

## 五、已知边界（显式接受）

1. **开关关闭时 G3 崩溃窗口依然存在**：默认配置只给 `batchInsert` 加了事务包裹，
   事件仍直发。这是灰度策略的代价，不是遗漏。
2. **单表写入热点**：`outbox_event` 是单表规则，上传 QPS 全量进单表会制造新热点
   （与 D1「评论不落 Outbox」同一条理由）。上线前需按实测 QPS 评估。
3. **本地通道的进程内事件崩溃窗口**：`LOCAL` 下 Handler 把事件交给应用内事件总线后
   即视为投递成功；若进程在异步消费前崩溃，该帖仍会停在 PENDING。
   Outbox 保证的是「事件行至少被投递一次到传输层」，不是「审核一定完成」。
   需要端到端严格可靠时用 `mq` 通道（消息持久化）。
4. **幂等结果缓存语义变化**：`MediaUploadFinalizer` 的 `idempotency.store` 由
   「失败即作废整帖」改为 fail-open 告警。
5. **墓碑/防旧不在本开关内**：时间线 DLQ 防旧由 PR-D3 单独提供。

---

## 六、本次新增的发件箱事件类型（同批落地，启动期校验）

| 类型 | Handler | 失败语义 |
|---|---|---|
| `STORAGE_DELETE` | `StorageDeleteHandler` | `deleteOrThrow` 异常上抛 → 退避重试 → DEAD 告警；not-found 幂等成功 |
| `MEDIA_UPLOADED` | `MediaUploadedHandler` | MQ 通道不可用时上抛留行，**不**静默降级本地 |

`OutboxHandlerRegistry` 在启动期校验「每个枚举值有且只有一个 Handler」，
新增枚举值必须与 Handler 同批落地，否则应用直接启动失败并点名缺失类型。
