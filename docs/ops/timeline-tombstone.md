# PR-D3 时间线墓碑防旧（DLQ / 发件箱重投）

> 对应 ADR：`docs/adr/0016-pr-bcd-implementation-plan.md` §5。本文记录**实际落地口径**，与 ADR 有差异处已标注。
> 状态：已实施，默认开启（`TURBOFEED_FEED_TIMELINE_TOMBSTONE_ENABLED=true`），可一键回退。

---

## 一、要挡住的事故

`TIMELINE_APPEND` 的发件箱事件行可能判死（DEAD），之后同一帖又被 `TIMELINE_REMOVE` 下架。
`OutboxDeadLetterSweeper` 重投那条 DEAD APPEND 时，引擎若无脑写入，**已下架内容会在公域复活**。
顺序消息只保证同一队列内按序，挡不住「跨通道 / 事隔数小时」的重投；引擎自己的 DLQ 重放同理。

验收场景（ADR §5）：`APPEND 判死 → REMOVE 成功 → Sweeper 重投 APPEND` → 帖子不出现、
`feed_timeline_stale_append_blocked_total` +1、事件行标 SENT 不再重试。

---

## 二、判定口径

| 项 | 口径 |
| --- | --- |
| 时刻来源 | **网关 enqueue 时刻**，固化在 `TimelineAppendPayload` / `TimelineRemovePayload` 的 `occurredAt` 字段 |
| 墓碑键 | `tf:timeline:tombstone:{timelineKey}`，值 = REMOVE 时刻的 epoch 毫秒字符串 |
| 墓碑 TTL | 默认 30 天（`TURBOFEED_FEED_TIMELINE_TOMBSTONE_TTL_DAYS`，≤0 回落 30 天） |
| 拦截条件 | `append.occurredAt <= tombstone` |
| 放行条件 | 无墓碑 / `occurredAt == null`（存量事件）/ `append.occurredAt > tombstone` |
| 拒绝语义 | **静默 return，绝不抛异常** → 发件箱标 SENT、MQ 正常 ACK、不写时间线、不建标签索引、不失效缓存 |
| 指标 | `feed_timeline_stale_append_blocked_total`（ADR 指定名，不带 `tf_` 前缀） |

`append.occurredAt > tombstone` **必须放行**：申诉翻案、重新过审会产生更晚的合法 APPEND，
一刀切永久封杀会让正常内容永远进不了公域。

---

## 三、与 ADR 的两处事实修正

1. **ADR 把墓碑落点写成「引擎侧 `FeedTimelinePublisher.remove` 落地处」不准确。**
   `FeedTimelinePublisher` 是**网关侧端口**（防腐层出口），引擎侧唯一写入口是
   `FeedTimelineDeliveryService.deliver`——HTTP 兜底、RocketMQ 顺序消息、引擎 DLQ 重放
   三条通道最终都汇到这里。判定放在此处才能覆盖全部通道；放在网关 Relay 既看不到
   引擎的下架状态，也覆盖不了引擎自己的 DLQ 重放。

2. **`FeedTimelineEvent` 契约本来就带 `occurredAt`，但原来的 `append/remove` 工厂每次都用 `Instant.now()`。**
   若沿用工厂，DEAD 行重投时 Handler 从 payload 重建事件会打上「重投时刻」的新时间戳，
   比同一帖后续 REMOVE 更晚，墓碑比对**必然漏判**。因此本项真正的工作量不是「加字段」，
   而是把 enqueue 时刻**固化进 payload 并全程透传**：

| 环节 | 改动 |
| --- | --- |
| enqueue（4 处） | `ReviewTimelineDelivery.publishAppend/publishRemove`、`MediaReviewService.reviewByMediaId`、`MediaUploadService` 删除路径 → payload 带 `Instant.now()` |
| Handler | `TimelineAppendHandler` / `TimelineRemoveHandler` → 调 `FeedTimelinePublisher` 带时刻重载，透传 `payload.occurredAt()` |
| HTTP 通道 | `HttpFeedTimelinePublisher` → `FeedEngineClient.append/remove(..., occurredAt)` → query 参数 `occurredAt` → 引擎 `FeedTimelineController` 解析并 `withOccurredAt` |
| MQ 通道 | `RocketMqFeedTimelinePublisher` → `FeedTimelineEvent.withOccurredAt`（落在**消息体**，DLQ 重放才能原样带回） |
| 引擎判定 | `FeedTimelineDeliveryService.deliver` → `TimelineTombstoneStore` |

`FeedTimelinePublisher` 的新重载是 `default` 方法，委托到原两参签名，因此
`FeedBackfillService` 等存量调用点与所有 Mock 实现零改动。
`FeedTimelineDeliveryService` 用**可选字段注入**墓碑组件，保留既有三参构造器，裁剪部署时判定整体跳过。

---

## 四、开关与部署接线

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `TURBOFEED_FEED_TIMELINE_TOMBSTONE_ENABLED` | `true` | 引擎侧总开关；`false` = 不读墓碑、不写墓碑、不拦截 |
| `TURBOFEED_FEED_TIMELINE_TOMBSTONE_TTL_DAYS` | `30` | 墓碑保留天数 |

- `tf-feed-engine/src/main/resources/application.yml`：`feed.timeline.tombstone-enabled` / `tombstone-ttl-days`
- `deploy/compose/docker-compose.prod.yml`：已在 **engine 段**显式转发两个变量。
  ⚠️ compose 的 `environment:` 是白名单，不转发则 yaml 占位符永远取默认值，回退开关会静默失效。
- `deploy/env/.env.multinode.example`：已附注释化回退示例。

**为什么默认开启而不是默认关闭**：零回归由「存量事件行没有 `occurredAt` → 反序列化为 `null` →
一律放行」保证，不依赖默认关。防旧是内容安全修复，默认关等于没修；同时保留 `false`
作为误拦时的紧急回退。

---

## 五、验证

1. 单元测试（无需中间件）：
   - `TimelineTombstoneStoreTest`：陈旧/合法判定、等于边界、脏值、Redis 故障 fail-open、Lua 只前进、TTL 回落、指标。
   - `FeedTimelineTombstoneDeliveryTest`：拒绝时零副作用且不抛异常、REMOVE 写墓碑顺序、下架失败不写墓碑、组件缺失零回归。
   - `TimelinePayloadOccurredAtTest`：enqueue 时刻固化与重投原样透传、存量 JSON 兼容。
   - `TimelinePublisherOccurredAtTest`：HTTP / MQ 双通道透传、存量两参调用零改动。
   - `SharedContractJsonTest`：`withOccurredAt` 不改契约结构、无幽灵字段。
2. 整仓 `mvn -Plocal test` + `mvn -Plocal package -DskipTests`。
3. 真链路（需起 Redis + 网关 + 引擎）验收 ADR 场景，核对指标 +1 与事件行 `SENT`。

---

## 六、回滚

把引擎侧 `TURBOFEED_FEED_TIMELINE_TOMBSTONE_ENABLED=false` 并重启即可整体回退：
不读墓碑、不写墓碑、不拦截，回到改造前行为。网关侧 payload 多一个字段不影响旧消费者
（`FAIL_ON_UNKNOWN_PROPERTIES` 默认关闭），无需同步回滚网关。

---

## 七、已知边界（未在本项解决）

| 边界 | 影响 | 建议 |
| --- | --- | --- |
| 墓碑 TTL 到期后超迟到的 APPEND 重投 | 仍可能复活 | 30 天已覆盖常规 DLQ / 发件箱滞留窗口；若要更强保证需持久化下架水位（DB），本项未做 |
| 跨实例时钟偏差 / 时钟回拨 | 可能误拦或漏拦 | 部署侧保证 NTP 同步；紧急时关总闸 |
| Redis 不可用 | 墓碑读失败按「无墓碑」放行（fail-open） | 时间线本身也依赖同一 Redis，此时即使放行也写不进去；若读失败就拒绝会让 Redis 抖动期间全站停发 |
| 存量事件行（无 `occurredAt`） | 不参与防旧，仍可能被重投复活 | 上线前清理已知 DEAD 行，或对历史数据做一次性水位回填 |
| 同一帖多次下架 | Lua 保证墓碑只前进不回退 | 旧 REMOVE 乱序重放不会把水位改小 |
| 引擎 DLQ 重放 | 消息体原样带回 `occurredAt`，判定生效 | 已在 MQ 通道把时刻落在消息体而非 MQ 属性 |
