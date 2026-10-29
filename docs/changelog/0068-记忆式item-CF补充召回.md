# 0068 记忆式 item-CF 补充召回通道（收藏又收藏 / 看了又看）

> 状态：**已落地 · 编译验证通过 · 单测全绿**（引擎 `ItemCfRecallChannelTest` 7 + 网关 `ItemCooccurrenceServiceTest` 5 + `MediaFavoriteServiceTest` 2 共现接入）
> 关联：多路召回蓝图（`recommendation-design.md`）、收藏本体（`user-favorite-design.md`）、ML 引入时机（`ml-introduction-plan.md`）
> 设计文档：`docs/architecture/item-cf-recall-design.md`（已落地，与代码同步）

## 一、为什么需要（补的是「行为共现」召回缺口）

双塔 / HNSW（A0/A1，模型化向量召回）解决「语义相近」，但**冷启动 / 长尾内容**缺训练样本时召回弱。
而「用户 A 收藏了 X 和 Y，X 与 Y 强共现」这类**行为共现信号**不依赖内容向量、不依赖训练、收藏即生效，
是项目里最强显式意图信号（权重 1.5，与 COMMENT/SHARE 同级）。

item-CF 定位为 P1 补充召回通道（ML 引入时机见 `ml-introduction-plan.md`）：在双塔已覆盖向量侧的前提下，
用最低成本补上「行为共现」这一路——**记忆式共现，与模型化 CF 互补而不替代**，都作为个性化位并入多路召回，
最终由 `RankingModel` 统一排序。

## 二、设计

- **两段式、跨服务单一真值**：网关在**收藏 / 取消收藏**时增量维护共现 ZSET
  （`tf:cf:item:{postId}`，对称 `ZINCRBY`），引擎 `ItemCfRecallChannel` 以用户「我的收藏」ZSET
  （`tf:favorite:user:{userId}`）为**种子**查邻居、merge 去重。两个 key 前缀统一收敛到
  `tf-shared#RedisKeys`，杜绝双份硬编码漂移。
- **共现矩阵（写侧，网关独占）**：`tf:cf:item:{postId} => ZSET<neighborPostId, coocWeight>`；
  TTL 14 天（冷内容自然消退、热内容持续续期）；单帖邻居上限 `maxNeighbors=500`（超裁尾）；
  增量边权重 `favoriteWeight=1.5`，取用户最近 `maxSeeds=50` 条收藏做对称双向 `ZINCRBY`，
  取消对称 `ZINCRBY(-w)`、权重归零 `ZREM`。
- **种子来源（读侧，引擎只读）**：`tf:favorite:user:{userId}`（由 `MediaFavoriteService#addToUserFav` 维护，已落地），
  `reverseRange(0,19)` 取最近 20 篇收藏作种子。该私有常量本次收敛到 `RedisKeys.USER_FAVORITE`。
- **批重建（默认关）**：`ItemCooccurrenceService#rebuildAll` 读 `media_favorite` 全表两两累加，
  `DEL 旧键 + pipeline ZADD 邻居 + 续期`；仅用于初次构建 / Redis 拓扑重建后回灌，不挂默认调度。
- **fail-open 贯穿**：共现写入（网关异步块）、共现读取（引擎召回）任一异常只告警不抛，
  不阻断收藏主流程或推荐主流程。
- **接入极小、可灰度可回滚**：新增 `RecallType.CF`（PRE_POOL，插在 interest 之后、vector 之前），
  占比由 `turbofeed.feed.cf-recall-ratio`（默认 0.1）控制，`=0` 即关闭、读路径退化为改造前语义。

## 三、改动清单（接入点，5 处流水线改动）

| 改动点 | 位置 | 内容 |
|---|---|---|
| 枚举 | `RecallType` | 新增 `CF` |
| 排除集 | `RecallContext` | 新增 `cfKeys`（`LinkedHashSet`） |
| 配比 | `RecommendationPipeline` | 新增 `@Value cf-recall-ratio:0.1` |
| 门控 | `slotsFor` | `switch` 加 `case CF -> cfRecallRatio`，`userId==null` 返回 0 |
| 池去重 | 池解析 | 跳过 `ctx.cfKeys.contains(it.timelineKey())` |
| 装配 | `recommend` | `preParts` 取 `cfPart`；终序热点→关注→兴趣→**cf**→向量→池；`remainingSlots` 减 `cfPart.size()`；cf 候选进审核信号集合 |
| 向量去重 | `VectorFeedRecallChannel` | 候选收集/part 构建额外排除 `cfKeys` |

## 四、文件清单

- 改 `tf-shared/.../redis/RedisKeys.java`：新增 `USER_FAVORITE` / `CF_ITEM` 常量及构造方法（跨服务单一真值）
- 新增 `tf-gateway/.../service/like/ItemCooccurrenceService.java`：共现矩阵增量（onFavorite/onUnfavorite）+ 批重建（rebuildAll），fail-open
- 改 `tf-gateway/.../service/like/MediaFavoriteJdbcRepository.java`：新增 `listAllUserPostPairs`（批重建用）
- 改 `tf-gateway/.../service/like/MediaFavoriteService.java`：构造器接入 `ItemCooccurrenceService`；收藏/取消异步块触发共现维护；`USER_FAV_KEY_PREFIX` 迁移到 `RedisKeys.USER_FAVORITE`
- 改 `tf-feed-engine/.../timeline/RecallType.java`：新增 `CF`
- 改 `tf-feed-engine/.../timeline/RecallContext.java`：新增 `cfKeys` 排除集
- 新增 `tf-feed-engine/.../timeline/ItemCfRecallChannel.java`：item-CF 召回通道（PRE_POOL，@Order(3)）
- 改 `tf-feed-engine/.../timeline/RecommendationPipeline.java`：`cf-recall-ratio` 配置、`slotsFor` 分支、池去重、`cfPart` 装配、审核候选收集
- 改 `tf-feed-engine/.../timeline/VectorFeedRecallChannel.java`：候选收集/part 构建额外排除 `cfKeys`
- 新增测试：`tf-gateway/.../service/like/ItemCooccurrenceServiceTest.java`（5）、`MediaFavoriteServiceTest.java`（+2 共现接入）、`tf-feed-engine/.../timeline/ItemCfRecallChannelTest.java`（7）
- 文档：`docs/architecture/item-cf-recall-design.md` + 本 changelog `0068`

## 五、验证证据

- 编译：`mvn -o -Plocal compile` 全模块通过（无 repackage，D 盘 mmap 限制规避）。
- 单测（纯 Mockito，无中间件依赖）：
  - 引擎 `ItemCfRecallChannelTest`（7）：有种子→返回共现邻居且排除已收藏/关注作者/热点/兴趣；无种子→空；`userId==null`→空；fail-open；type/phase 契约。
  - 网关 `ItemCooccurrenceServiceTest`（5）：`onFavorite` 对称双向 +w、`onUnfavorite` 衰减归零 ZREM、`trim` 封顶、`rebuildAll` 覆盖写、fail-open。
  - 网关 `MediaFavoriteServiceTest`（+2）：收藏异步块触发 `onFavorite`、取消触发 `onUnfavorite`。
- 回归：全引擎 + 全网关套件编译绿（`tf:cf:item:` / `tf:favorite:user:` 经 `RedisKeys` 单一真值，清理点成对补齐）。

## 六、配置项 / 环境注意

| 配置 | 默认 | 说明 |
|---|---|---|
| `turbofeed.feed.cf-recall-ratio` | `0.1` | item-CF 占每页槽位比例；`=0` 关闭 |
| `turbofeed.feed.cf.max-seeds` | `50` | 单次收藏关联的其他收藏上限（控制写放大） |
| `turbofeed.feed.cf.max-neighbors` | `500` | 单帖共现邻居上限（ZSET 内存封顶） |
| `turbofeed.feed.cf.favorite-weight` | `1.5` | 单条「同时收藏」边权重 |

- 共现矩阵是纯 Redis ZSET，**零新增中间件**；批重建读现有分片库 `media_favorite`，严禁高频调用（全表内存化）。
- item-CF 不训练、不进 embedding，只是多路召回一路；`cf-recall-ratio=0` 即完全退化为改造前语义，可灰度可回滚。
