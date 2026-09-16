# tf-feed-engine 技术文档

> 公域 Feed 时间线读模型（独立 Spring Boot 服务，端口 8083，父包 `com.turbofeed.feedengine`）。
> **当前状态：已实现「公域时间线读模型」**；「推拉结合」的扇出部分（收件箱 / 大 V outbox / 活跃度分层 /
> 多路归并）**仍是未实现蓝图**，源码中仅有空包占位（见 §5）。

## 1. 为什么先做「时间线读模型」

拆分前，公域推荐流是**跨分片广播扫全表**（`listApprovedGlobal`）——每翻一页就广播到全部 4 个分片再归并，
百亿行下会拖垮所有分片。18 号改造把它换成**写时物化**：审核通过的那一刻，把内容写入 Redis ZSET 时间线，
读路径只做 ZSET 游标读，**零扫分片库、每请求 O(log n)**。B1 又把这份读模型整体搬到独立进程，网关不再持有任何 Feed 读模型。

所以本模块当前的真实职责是**「公域时间线的读写与推荐流缓存」**，而不是「推拉结合扇出」。

## 2. 代码结构（5 个类）

```
com.turbofeed.feedengine
├── FeedEngineApplication          # 启动类
└── timeline/
    ├── FeedTimelineStore          # 时间线写/读存储（ZSET 分桶 + 反查索引）
    ├── RecommendedFeedService     # 推荐流读取 + 短 TTL 旁路缓存
    ├── FeedTimelineController     # 内部接口 /internal/feed/**（供网关调用）
    └── FeedTimelineConsumer       # RocketMQ 顺序消息消费者（mq.enabled=true 时激活）
```

## 3. 数据结构（Redis）

| Key 模式 | 类型 | 内容 | TTL |
|---|---|---|---|
| `tf:feed:tl:{pool}:{yyyyMMdd}` | ZSET | 某流量池某日的公域时间线；member = `FeedItemView` JSON，**score = 入流时刻** | 7 天 |
| `tf:feed:idx:{timelineKey}` | String | **反查索引**：`{bucketKey}\u0001{member}`，用于精确 `ZREM` | 7 天 |
| `tf:feed:rec:{page}:{limit}` | String | 推荐流旁路缓存（整页结果） | **15s** |

关键设计：

- **按天分桶**：避免单个 ZSET 无限膨胀。读路径合并近 `MERGE_BUCKETS = 3` 天的分桶，按 score 归并；
- **多池**：`MAX_POOL_LEVELS = 3`（对应信用分级的 L0/L1/L2 → 池 1/2/3），`poolLevel < 1` 兜底为 1，超出封顶；
- **score 取「入流时刻」而非 `createdAt`**：上传时间决定桶会让「昨天上传、今天过审」的内容落进旧桶，
  而读路径只并最近 3 天分桶 → 该内容**永远不会出现在发现流**。统一以过审时刻分桶与打分，保证「今天过审 → 今天可见」；
- **反查索引**：因为 member JSON 不包含所在桶信息，下架时必须靠索引精确定位到「哪个桶 + 哪个 member」才能 `ZREM`，
  否则只能扫桶。索引与桶同 TTL，避免悬挂。

## 4. 读路径与一致性

```
GET /internal/feed/recommended?page=&size=
  └─ RecommendedFeedService
       ├─ 命中 tf:feed:rec:{page}:{limit}  → 直接返回
       └─ 未命中 → 合并近 3 天 × 3 池 ZSET，按 score 倒序游标分页 → 回填缓存（15s）
```

**下架是最终一致的**：为避免批量失效造成 Redis 阻塞，下架时**不主动清推荐流缓存**，因此删帖 / 下架后
**最多 15s 内公域仍可能返回旧页**。这是刻意的取舍（简单 + 无阻塞），已作为验收口径确认。

## 5. 未实现蓝图：推拉结合扇出

`com.turbofeed.feed.{fanout,inbox,outbox,aggregate,config}` 五个包**目前只有 `.gitkeep` 占位、无任何实现**。

设计意图（待实现）：

```
发布事件 ──> 扇出 Worker（活跃度分层过滤）
              ├─ 普通用户：推 → 收件箱 Redis List（LTRIM 定长）
              └─ 大 V（粉丝 > push-threshold）：只写 outbox（ZSET）

读 Feed ──> 多路归并：收件箱（推的部分） + 关注列表中大 V 的 outbox（拉的部分）
```

| 机制 | 规划落点 | 配置键（已占位） |
|---|---|---|
| 收件箱 | Redis List + LTRIM 定长裁剪 | `turbofeed.fanout.inbox-max-size: 2000` |
| 大 V 判定 | 粉丝数阈值，超阈值转拉模式 | `turbofeed.fanout.push-threshold: 1000` |
| outbox | 大 V 发布时间线（ZSET） | `turbofeed.fanout.outbox-retain-days: 7` |
| 活跃度分层 | N 日未登录跳过扇出 | `turbofeed.fanout.inactive-days: 7` |
| 多路归并 | 收件箱 + outbox 归并，首屏条数 | `turbofeed.fanout.inbox-read-size: 500` |

> 这些配置键当前**只是占位**，扇出逻辑尚未实现，改它们不会改变任何行为。

## 6. 写入路径

网关不直接写 Redis，而是经 `FeedTimelinePublisher` 端口投递（见 [tf-gateway 文档 §5](tf-gateway.md)）：

| 实现 | 激活条件 | 语义 |
|---|---|---|
| `HttpFeedTimelinePublisher`（网关侧） | `turbofeed.mq.enabled=false`（默认） | 同步 HTTP 调本服务 `/internal/feed/timeline/*` |
| `RocketMqFeedTimelinePublisher`（网关侧） | `turbofeed.mq.enabled=true` | RocketMQ **顺序消息**，本服务的 `FeedTimelineConsumer` 消费 |

**顺序性不可妥协**：`append` 与 `remove` 共用同一 topic 与同一消费者、靠消息体 `action` 区分，
发送侧 `syncSendOrderly(hashKey = timelineKey)`、消费侧 `ConsumeMode.ORDERLY`。
若按 `action` 拆 tag / 拆消费者，同一帖的 append 与 remove 会落进不同队列而失去顺序，
出现「remove 先执行、append 后执行 → 已下架内容重新出现」的**内容安全事故**。

`hashKey` 必须是**帖身份**（`timelineKey`：有 `postId` 用 `postId`，历史单图数据回退 `mediaId`），
这样一帖多图的多张图共享同一队列。

## 7. 内部接口

```
GET  /internal/feed/recommended?page=&size=          # 推荐流分页（网关转发）
POST /internal/feed/timeline/append?poolLevel=       # 入流（幂等：同 key 先摘旧位置再写新位置）
POST /internal/feed/timeline/remove?mediaId=         # 下架
```

路径前缀 `/internal/**` 与对外 `/api/**` **物理区分**，便于在网关 / 前置层拒绝外部直达。

> ⚠️ **当前无鉴权**：依赖内网信任。生产必须补服务间令牌 / mTLS，否则任何能访问 8083 的调用方都可写入 / 删除公域时间线。

## 8. 依赖边界

- 依赖 `tf-shared`（契约 `FeedItemView` / `FeedTimelineEvent`）；
- Redis 客户端为官方 `spring-boot-starter-data-redis`（**Lettuce** + `commons-pool2`）——
  **不使用 Redisson**（只需要 ZSET / String 原子命令，不需要分布式锁）；
- **不依赖 `tf-hotspot`**：热点治理 SDK 尚无实现（0 个源文件），当前无任何模块依赖它；
- 不连 MySQL：本服务是纯 Redis 读模型，无数据源。

## 9. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8083 | 原 8082 已避让 RocketMQ broker 占用 |
| `spring.data.redis.host / port / password` | localhost:6379 / `${TURBOFEED_REDIS_PASSWORD:}` | Lettuce 懒连接；口令外部化，未注入而 Redis 要求鉴权时直接 NOAUTH |
| `management.health.redis.enabled` | false | Redis 就绪前关闭健康指示器，防「克隆即跑」被拉 DOWN |
| `turbofeed.mq.enabled` | false | 由 `mq` profile 加载 `application-mq.yml` 后置 true，才装配 `FeedTimelineConsumer` |
| `turbofeed.fanout.*` | — | **占位**，扇出未实现 |

## 10. 已做 vs 未做

- ✅ 时间线读模型（多池 ZSET 分桶 + 反查索引 + 游标分页）、推荐流 15s 旁路缓存、
  `/internal/**` 接口、RocketMQ 顺序消费、独立部署单元。
- ⬜ 推拉结合扇出（收件箱 / outbox / 活跃度分层 / 多路归并）——仅有空包与配置占位。
- ⬜ 服务间鉴权（令牌 / mTLS）。
- ⬜ 服务注册与发现：当前网关**直连 `base-url`**，无注册中心与客户端负载均衡。
