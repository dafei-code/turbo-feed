# tf-feed-engine 技术文档

> 公域 Feed 时间线读模型（独立 Spring Boot 服务，端口 8083，父包 `com.turbofeed.feedengine`）。
> **当前状态：已是推荐服务 + 公域时间线读模型**：多路召回 / 粗排 / 精排 / 三层重排、三级流量池、关注流读均已落地；
> 关注流的大 V 优先扫描、作者索引分片与 L1 缓存也已实现（默认保持旧行为，按配置 opt-in）。
> 传统「推拉结合扇出」（收件箱 / 大 V outbox / 活跃度分层 / 多路归并）**仍是未实现蓝图**（见 §5）。

## 1. 为什么先做「时间线读模型」

拆分前，公域推荐流是**跨分片广播扫全表**（`listApprovedGlobal`）——每翻一页就广播到全部 4 个分片再归并，
百亿行下会拖垮所有分片。18 号改造把它换成**写时物化**：审核通过的那一刻，把内容写入 Redis ZSET 时间线，
读路径只做 ZSET 游标读，**零扫分片库、每请求 O(log n)**。B1 又把这份读模型整体搬到独立进程，网关不再持有任何 Feed 读模型。

所以本模块当前的真实职责是**「公域时间线的读写与推荐流缓存」**，而不是「推拉结合扇出」。

## 2. 代码结构

> **按职责分组，不逐一列举类名。** 本节原先写「5 个类」并列了 4 个，而实际已有 67 个——
> 这种「枚举式目录树」必然腐坏（每加一个类就假一分），所以改成稳定的职责边界表；
> 需要精确类表时以 `src/main/java` 为准。

| 包 | 职责 | 关键类 |
|---|---|---|
| `timeline/` | 公域时间线读模型 + 推荐漏斗编排 | `FeedTimelineStore`（池 ZSET 与反查索引的读写门面）· `RecommendationPipeline`（漏斗编排：多路召回 → 粗排 → 精排 → 重排）· `TimelineSupport`（**桶键与反查索引格式的唯一真值** + 共享纯函数）· `RecommendedFeedService`（15s 旁路缓存）· `FeedTimelineController` / `FeedTimelineConsumer`（内部 HTTP 与 MQ 两个写入口）· `FeedPoolPromoter`（赛马晋级调度）· `DiversityRerankService` / `EcosystemRegulationService` / `ColdStartService` / `PlayabilityService` / `TimelineDedup`（策略与去重） |
| `recall/` | 多路召回通道与向量基础设施 | `VectorRecallChannel` / `TagVectorRecallChannel`、`ItemEmbeddingSource` / `UserEmbeddingSource` 家族（hash / model / ann 三种模式）、`VectorIndexWriter`（HNSW） |
| `ranking/` | 粗排与精排模型 | `PreRankFilter` / `LinearPreRankModel`、`RankingModel` 家族（`LinearWeightedRankingModel` / `MultiTargetRankingModel` / `DeepFmRankingModel`）、`SelectedRankingModel`（按 `modelType` 分发的 `@Primary`） |
| `interest/` | 兴趣标签、会话序列、搜索运维 | `InterestService`、`SessionSequenceService`、`TagIndexService`、`TagScoreBackfillService` |
| `social/` · `client/` · `config/` · `interceptor/` · `logging/` · `trace/` · `contract/` | 关注关系 / 外部客户端 / 装配 / 内部令牌拦截 / 行为日志 / traceId / 跨服务契约 | `FollowService`（大 V 优先扫描 + 动态配额）、`GatewayReviewClient`、`InternalTokenInterceptor`、`TraceContext` |
| `timeline/` 内的关注流读优化 | 作者索引分片与本地 L1 | `TimelineSupport.authorIndexKey/authorIndexShardFor`（key 格式唯一出口）、`RecallSupport`（viewer hash 读分片）、`AuthorRecentCache`（复用 tf-hotspot SDK，5s TTL-only） |

**一条硬约定**：桶键 `tf:feed:tl:{pool}:{yyyyMMdd}` 与反查索引值 `{bucketKey}\u0001{member}` 的
拼装/解析**只允许出现在 `TimelineSupport`**（`bucketKey` / `indexValue` / `parseIndex` 等）。
写路径、读路径、扫描路径各有 5+ 处需要它们，任一处自行拼装就会出现「写入与读取落到两套键」——
而两边代码各自看都是对的，症状是「发布了但发现流没有」。

## 3. 数据结构（Redis）

| Key 模式 | 类型 | 内容 | TTL |
|---|---|---|---|
| `tf:feed:tl:{pool}:{yyyyMMdd}` | ZSET | 某流量池某日的公域时间线；member = `FeedItemView` JSON，**score = 入流时刻** | 7 天 |
| `tf:feed:idx:{timelineKey}` | String | **反查索引**：`{bucketKey}\u0001{member}`，用于精确 `ZREM` | 7 天 |
| `tf:feed:rec:{page}:{limit}` | String | 推荐流旁路缓存（整页结果） | **15s** |
| `tf:feed:author:{authorId}` 或 `tf:feed:author:{authorId}:shard:{n}` | ZSET | 作者近期内容索引；写侧按分片数写 N 份，读侧按 viewer hash 选 1 份，下架删全部；格式只在 `TimelineSupport` 拼装 | `author-index-ttl-hours`（默认 24h） |

关键设计：

- **按天分桶**：避免单个 ZSET 无限膨胀。读路径合并近 `MERGE_BUCKETS = 3` 天的分桶，按 score 归并；
- **多池**：`MAX_POOL_LEVELS = 3`（对应信用分级的 L0/L1/L2 → 池 1/2/3），`poolLevel < 1` 兜底为 1，超出封顶；
- **score 取「入流时刻」而非 `createdAt`**：上传时间决定桶会让「昨天上传、今天过审」的内容落进旧桶，
  而读路径只并最近 3 天分桶 → 该内容**永远不会出现在发现流**。统一以过审时刻分桶与打分，保证「今天过审 → 今天可见」；
- **反查索引**：因为 member JSON 不包含所在桶信息，下架时必须靠索引精确定位到「哪个桶 + 哪个 member」才能 `ZREM`，
  否则只能扫桶。索引与桶同 TTL，避免悬挂。
- **关注流大 V 治理**：关注流是读扩散（写作者索引，读关注列表），真正瓶颈是热 key 与扫描窗口截断。`FollowService.rankedFollowees`
  在配置开启时用 Redis pipeline 读粉丝数，把大 V 排到扫描窗口前部，并对大 V 用更小的 per-author cap 防霸屏；
  关闭时等价于原 `followedAuthors`，零回归。
- **作者索引分片副本**：`shards>1` 时写侧写 `:shard:0..N-1` 共 N 份，读侧用 `floorMod(viewerId.hashCode(), N)` 选 1 份，
  匿名/空 viewer 固定读 shard 0；`shards=1` 保持历史单 key。切分片前必须先回填存量，否则旧数据只在无后缀 key 上。
- **作者近期内容 L1**：`AuthorRecentCache` 复用 `tf-hotspot` 的 `LocalCacheWithBroadcast`，默认 5s TTL-only（不广播），
  因为关注流是近似信号，短延迟可接受。

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

> ⚠️ 与 §3 的 P0/P1 优化区分：大 V 优先扫描、作者索引分片、作者 L1 缓存属于**关注流读侧优化**，不是传统扇出。
> 真正的收件箱 / 大 V outbox / 活跃度分层 / 多路归并仍未实现，下面这段保持蓝图状态。

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

路径前缀 `/internal/**` 与对外 `/api/v1/**` **物理区分**，便于在网关 / 前置层拒绝外部直达。

> ⚠️ **已接 `X-Internal-Token` 双侧校验**（值来自 `TURBOFEED_INTERNAL_TOKEN`）：`dev`/`local` 缺令牌可 fail-open 便于克隆即跑，
> 其它 profile 缺令牌启动即失败。生产仍建议叠加网络层隔离 / mTLS，避免任何能访问 8083 的调用方写入或删除公域时间线。

## 8. 依赖边界

- 依赖 `tf-shared`（契约 `FeedItemView` / `FeedTimelineEvent`）；
- Redis 客户端为官方 `spring-boot-starter-data-redis`（**Lettuce** + `commons-pool2`）——
  **不使用 Redisson**（只需要 ZSET / String 原子命令，不需要分布式锁）；
- **依赖 `tf-hotspot`**：`LocalCacheWithBroadcast` 已用于热点榜 L1（`HotTimelineKeyCache`）与作者近期内容 L1（`AuthorRecentCache`）；冷热桶迁移仍待做；
- 不连 MySQL：本服务是纯 Redis 读模型，无数据源。

## 9. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8083 | 原 8082 已避让 RocketMQ broker 占用 |
| `spring.data.redis.host / port / password` | localhost:6379 / `${TURBOFEED_REDIS_PASSWORD:}` | Lettuce 懒连接；口令外部化，未注入而 Redis 要求鉴权时直接 NOAUTH |
| `management.health.redis.enabled` | false | Redis 就绪前关闭健康指示器，防「克隆即跑」被拉 DOWN |
| `turbofeed.mq.enabled` | false | 由 `mq` profile 加载 `application-mq.yml` 后置 true，才装配 `FeedTimelineConsumer` |
| `turbofeed.feed.follow.bigv-first-enabled` | false | 开启后按粉丝数把大 V 排到关注扫描窗口前部 |
| `turbofeed.feed.follow.bigv-follower-threshold` / `bigv-scan-window` / `bigv-per-author-cap` | 100000 / 200 / 1 | 大 V 阈值、扫描窗口、大 V 单页 per-author 配额 |
| `turbofeed.feed.follow.author-index-shards` | 1 | 作者索引副本数；>1 时写入写 N 份、读按 viewer hash 选 1 份，切换前需回填 |
| `turbofeed.feed.follow.author-cache-enabled` / `author-cache-ttl-seconds` | true / 5 | 作者近期内容 L1 缓存及 TTL |
| `turbofeed.fanout.*` | — | **占位**，传统扇出未实现 |

## 10. 已做 vs 未做

- ✅ 时间线读模型（多池 ZSET 分桶 + 反查索引 + 游标分页）、推荐流 15s 旁路缓存、
  `/internal/**` 接口、RocketMQ 顺序消费、独立部署单元。
- ✅ 推荐漏斗（多路召回 + 粗排 + 精排 + 三层重排 + 流量池赛马 + 冷启动 + 生态调节）、行为日志归档、HNSW 向量召回。
- ✅ 关注流读侧优化：大 V 优先扫描 + 动态 per-author 配额、作者索引分片、viewer hash 读分片、作者近期内容 L1。
- ✅ 服务间鉴权：`X-Internal-Token` 双侧校验；服务发现：Nacos 双向 opt-in，默认直连 `base-url`。
- ⬜ 传统推拉结合扇出（收件箱 / outbox / 活跃度分层 / 多路归并）——仅有空包与配置占位。
- ⬜ 通知 WS/SSE、集中日志与全链路 Tracing。
