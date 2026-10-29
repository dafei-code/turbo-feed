# 记忆式 item-CF 补充召回通道（「收藏又收藏 / 看了又看」）

> 状态：**已落地（与代码同步）**——本方案已实现并编译验证通过，配套 changelog `0068`。
> 前置阅读：`docs/architecture/recommendation-design.md`（多路召回蓝图）、`docs/architecture/ml-introduction-plan.md`
> （ML 引入时机，item-CF 定位为 P1 补充召回）、`docs/architecture/user-favorite-design.md`（收藏本体与进画像）。

---

## 0. 结论先行

1. **item-CF 是「记忆式」共现召回**，与已落地的双塔 / HNSW（A0/A1，模型化向量召回）**互补而不替代**：
   前者捞「和你收藏/看过的东西**强共现**」的内容（可解释、零训练延迟、随收藏实时更新），后者捞「语义相近但未必同收藏」的内容。
   二者都作为个性化位并入多路召回，最终由 `RankingModel` 统一排序。
2. **两段式、跨服务单一真值**：网关在**收藏 / 取消收藏**时增量维护共现 ZSET（`tf:cf:item:{postId}`，对称 `ZINCRBY`），
   引擎 `ItemCfRecallChannel` 以用户「我的收藏」ZSET（`tf:favorite:user:{userId}`）为**种子**查邻居、merge 去重。
   两个 key 前缀统一收敛到 `tf-shared#RedisKeys`，杜绝双份硬编码漂移（本仓库已付过学费的失败模式）。
3. **零新中间件、复用现有 Redis + 分片库**：共现矩阵就是「每篇内容一个 ZSET」，毫秒级读取；批重建读 `media_favorite`
   全表两两累加（默认关，冷启动 / 灾备用）。
4. **fail-open 贯穿**：共现写入（网关异步块）、共现读取（引擎召回）任一异常都只告警不抛，绝不阻断收藏主流程或推荐主流程。
5. **接入点极小、可灰度可回滚**：新增一路 `RecallType.CF`（PRE_POOL，插在 interest 之后、vector 之前），占比由
   `turbofeed.feed.cf-recall-ratio`（默认 0.1）控制，`=0` 即关闭、读路径退化为改造前语义。

---

## 1. 需求与边界（为什么是 P1、为什么是 item-CF）

### 1.1 动机

- 双塔 / HNSW 解决「语义相近」召回，但**冷启动内容、长尾小众内容**缺乏向量训练样本时召回弱；
  而「用户 A 收藏了 X 和 Y，Y 和 X 强共现」这类**行为共现信号**，不依赖内容向量、不依赖训练，收藏即生效。
- 收藏是项目里**最强显式意图信号**（权重 1.5，与 COMMENT/SHARE 同级，`user-favorite-design.md` §1.2）。
  它的「共现」天然就是高质量的「看了又看 / 收藏又收藏」候选源。
- 引入时机（见 `ml-introduction-plan.md`）：P0 先把多路召回组件化跑通（已完成）；**P1 引入协同过滤作为补充召回通道**，
  item-CF 先于模型化 CF（双塔已覆盖向量侧），用最低成本补上「行为共现」这一路。

### 1.2 与双塔 / A1 的关系（定位，避免重复造轮子）

| 通道 | 形态 | 数据来源 | 擅长 | 不擅长 |
|---|---|---|---|---|
| 双塔 / HNSW（A0/A1） | 模型化向量召回 | 内容/用户 embedding + ANN 索引 | 语义相近、跨类目泛化 | 冷启动/长尾缺样本；训练有延迟 |
| **item-CF（本方案）** | **记忆式共现** | 收藏行为共现（Redis ZSET） | **强共现、可解释、零延迟、随收藏实时更新** | 不能泛化到「从未被同收藏过」的内容 |

> 二者是正交补充：**item-CF 不训练、不进 embedding**，只是多路召回里的「第 N 路」个性化位；排序权完全交给现有
> `RankingModel`，不会与向量召回抢地盘。后续若要「从交互标签联合训 item 塔」属增强，非本方案范围。

### 1.3 范围边界

| 能力 | 本期 | 说明 |
|---|---|---|
| item-CF 增量共现（收藏时维护） | ✅ | 网关 `ItemCooccurrenceService` |
| item-CF 召回通道（读共现、并入多路召回） | ✅ | 引擎 `ItemCfRecallChannel` + 流水线装配 |
| 批重建共现矩阵（冷启动 / 灾备） | ✅（默认关） | `rebuildAll`，读 `media_favorite` 全表，运维手动/定时触发 |
| 用「完播/点赞」共现 | ⏸ 后置 | 本期只用收藏（最强信号）；后续可把 `interaction_event` 共现并入同一 ZSET |
| 模型化 CF（联合训练 item 塔） | ⏸ 后置 | 与双塔/A1 同族，非本方案 |

---

## 2. 现状梳理（多路召回架构，改造的底座）

召回已组件化（`RecommendationPipeline` + `RecallChannel` 接口 + `RecallType`/`RecallPhase` 枚举）：

```
多池候选 → 粗排(G1) → 多路召回(热点/兴趣/item-CF/向量/关注) → 精排(RankingModel)
           → 多样性重排(G8) → 冷启动混排(G7) → 生态调控(G9)
```

| 通道 | `@Order` | `RecallPhase` | 种子/候选源 | 写出的排除集 |
|---|---|---|---|---|
| 热点 `HOT` | 1 | PRE_POOL | 热点榜 | `hotKeys` |
| 兴趣 `INTEREST` | 2 | PRE_POOL | 用户标签 → 内容倒排 | `interestKeys` |
| **item-CF `CF`（新增）** | **3** | **PRE_POOL** | **用户收藏 → 共现邻居** | **`cfKeys`** |
| 向量 `VECTOR` | 3 | POST_POOL | 流量池全量候选（依赖池解析） | `vectorKeys` |
| 关注 `FOLLOW` | 4 | POST_POOL | `tf:follow:{userId}` 社交图 | `followKeys` |

- 跨通道去重靠 `RecallContext` 的共享 `LinkedHashSet` 排除集（`hotKeys/interestKeys/cfKeys/vectorKeys/followKeys`），
  池解析与装配均读这些集合避免同页重复。
- item-CF 放在 **PRE_POOL、interest 之后**：可复用 `interestKeys` 排除已占兴趣位的内容；`vectorKeys` 尚未生成
  （POST_POOL 才跑），其重叠由装配期整页去重（`PageDedupRule`）兜底。
- 终序装配：**热点 → 关注 → 兴趣 → item-CF → 向量 → 流量池**（强信号位钉最前，个性化位先于通用排序）。

---

## 3. 数据模型设计

### 3.1 共现矩阵（写侧，网关独占）：`tf:cf:item:{postId}`

```
结构：ZSET<neighborPostId, coocWeight>
语义：coocWeight = 有多少用户「同时收藏了 postId 与 neighborPostId」的加权计数（单条边权重 favoriteWeight=1.5）
TTL  ：14 天（冷内容共现自然消退；热内容因持续 ZINCRBY 续期）
内存封顶：单帖邻居上限 maxNeighbors=500（超过裁最低权重尾）
```

**增量维护（常态）**——`ItemCooccurrenceService#onFavorite` / `onUnfavorite`：

```
用户收藏 A（addToUserFav 已先写入 tf:favorite:user:{userId}）：
  读该用户最近 maxSeeds(=50) 条收藏 → 对每条 other(≠A)：bumpPair(A, other)
    bumpPair(a,b): ZINCRBY tf:cf:item:a +w(b);  ZINCRBY tf:cf:item:b +w(a)   // 对称双向
                  trim 两键到 Top-500；touch 续期
取消收藏 A（removeFromUserFav 已先移除）：
  读剩余收藏 → 对每条 other：decayPair(A, other)
    decayPair(a,b): ZINCRBY -w; 权重归零即 ZREM
```

> **为什么对称 + 取最近 50 条**：对称保证 `tf:cf:item:A` 与 `tf:cf:item:other` 都含对方，召回任一侧都能查到；
> 取最近 50 条控制大 V 收藏量下的写放大。权重口径：增量用 `favoriteWeight=1.5`（强意图边），批重建用单位计数 1.0。

### 3.2 种子来源（读侧，引擎只读）：`tf:favorite:user:{userId}`

```
结构：ZSET<postId, score=收藏时刻ms>（由 MediaFavoriteService#addToUserFav 维护，已落地）
语义：用户近期收藏的内容身份（= timelineKey），作为 item-CF 召回的种子
读：reverseRange(..., 0, SEED_LIMIT-1) 取最近 20 篇
```

该 key 此前在 `MediaFavoriteService` 内私有硬编码（`USER_FAV_KEY_PREFIX`），本次**收敛到 `RedisKeys.USER_FAVORITE`**
（跨服务单一真值）；引擎召回与网关写都走同一个常量，避免「单边改、静默失配」。

### 3.3 批重建（默认关）：`ItemCooccurrenceService#rebuildAll`

```
读 media_favorite 全表 → 按 user_id 分组 → 组内两两累加共现（无向图，a-b 与 b-a 同权）
→ 对每篇内容：DEL 旧键 + ZADD 邻居（pipeline 写，按帖隔离，集群安全）+ 续期
```

- **成本**：全表对读入内存，**严禁高频 / 每分钟调用**；仅用于初次构建共现矩阵、或 Redis 拓扑重建后全量回灌。
- 受 `turbofeed.feed.cf.*` 开关约束；本期未挂 `@Scheduled`（避免空 cron 启动失败），保留为手动/运维触发方法，
  后续可按需接管理端点或 cron。

---

## 4. 接入设计（网关写 + 引擎读，两段式）

### 4.1 网关写侧：`MediaFavoriteService` → `ItemCooccurrenceService`

- `MediaFavoriteService` 构造器新增 `ItemCooccurrenceService` 依赖（Spring 自动注入）。
- 收藏主链路 `apply(..., favorite=true)`：在「异步落库」块内追加 `itemCooccurrenceService.onFavorite(postId, userId)`
  （此时 `tf:favorite:user:{userId}` 已含 A，读取即可关联其余收藏）。
- 取消收藏 `apply(..., favorite=false)`：异步块内追加 `itemCooccurrenceService.onUnfavorite(postId, userId)`
  （此时 A 已从 ZSET 移除，读取剩余收藏做对称衰减）。
- 二者均在 `interactionExecutor` 异步块内、fail-open 包裹，主响应不阻塞、不抛收藏异常。

### 4.2 引擎读侧：`ItemCfRecallChannel`（PRE_POOL）

```
recall(ctx, slots):
  if slots<=0 || userId==null: return []                       // 匿名/关闭 → 无 CF 信号
  seeds = reverseRange(tf:favorite:user:{userId}, 0, 19)       // 最近 20 篇收藏作种子
  if seeds empty: return []
  merged = {}                                                   // neighborPostId → 累计 coocWeight
  for seed in seeds:
    neighbors = reverseRangeByScoreWithScores(tf:cf:item:{seed}, 0, 49)
    for (nb, w) in neighbors: if nb in seeds: continue          // 已收藏的不必再推
      merged[nb] += w
  if merged empty: return []
  按累计权重降序取候选：排除 hotKeys/interestKeys/followeeAuthors，写 cfKeys → memberOf 还原 → part
  sortByScore(part, ctx)                                        // 召回只保证进得来，排序交 RankingModel
```

- 排除集：PRE_POOL 阶段 `hotKeys/interestKeys` 已就绪直接用；`followeeAuthors` 已就绪（关注位专属）；
  `vectorKeys` 未生成，重叠由装配期 `PageDedupRule` 整页去重兜底。候选写入 `cfKeys` 供流量池解析与向量召回去重。
- 跨服务 key：读 `tf:favorite:user:{userId}` 与 `tf:cf:item:{seed}` 均经 `RedisKeys` 门面（与网关同一真值）。

### 4.3 流水线装配（5 处改动，行为逐行对齐既有通道）

| 改动点 | 位置 | 内容 |
|---|---|---|
| 枚举 | `RecallType` | 新增 `CF` |
| 排除集 | `RecallContext` | 新增 `cfKeys`（`LinkedHashSet`） |
| 配比 | `RecommendationPipeline` | 新增 `@Value cf-recall-ratio:0.1` |
| 门控 | `slotsFor` | `switch` 加 `case CF -> cfRecallRatio`，`userId==null` 返回 0 |
| 池去重 | 池解析 | 跳过 `ctx.cfKeys.contains(it.timelineKey())` |
| 装配 | `recommend` | `preParts` 取 `cfPart`；终序热点→关注→兴趣→**cf**→向量→池；`remainingSlots` 减去 `cfPart.size()`；cf 候选进审核信号集合 |
| 向量去重 | `VectorFeedRecallChannel` | 候选收集与 part 构建额外排除 `cfKeys`（cf 已先跑，避免向量浪费槽位） |

---

## 5. 降级与一致性（fail-open 贯穿）

| 风险/场景 | 对策 |
|---|---|
| 网关共现写异常 | `ItemCooccurrenceService` 内部 try-catch + `onFavorite/onUnfavorite` 外层告警；主收藏链路不抛、不阻塞 |
| 引擎共现读异常（Redis 不可达 / 种子缺失） | `ItemCfRecallChannel` 返回空集 → 退化为「无 CF 信号」，推荐流其余通道正常 |
| 用户无收藏 | 无种子 → 直接返回空，CF 通道对该用户不生效（不影响其它个性化位） |
| 共现矩阵冷内容膨胀 | 单键 TTL 14 天 + `maxNeighbors=500` 封顶，热内容因持续 ZINCRBY 续期、冷内容自然消退 |
| 取消收藏未衰减干净 | 对称 `ZINCRBY(-w)` + 权重归零 `ZREM`；极端竞态下仅共现权重轻微偏差，不影响召回主流程 |
| 新增 Redis key 清理遗漏 | `tf:cf:item:` / `tf:favorite:user:` 经 `RedisKeys` 单一真值；全局搜清理点成对补齐（沿用 0062 经验） |
| 与双塔/A1 抢位 | 不抢：item-CF 只是多路召回一路，排序权统一交 `RankingModel`；占比 `cf-recall-ratio` 可灰度 |

---

## 6. 验证方案（遵循仓库「真启动 / 真 Redis / 端到端」标准）

| 层 | 断言 |
|---|---|
| `ItemCooccurrenceService` 单测 | `onFavorite` 对称写入（A↔other 双向 +w）、`onUnfavorite` 衰减归零 ZREM、`trim` 封顶、`rebuildAll` 覆盖写、fail-open |
| `MediaFavoriteService` 共现接入 | 收藏异步块触发 `onFavorite`、取消触发 `onUnfavorite`（Mockito 直构造，无中间件） |
| `ItemCfRecallChannel` 单测 | 有收藏种子→返回共现邻居且排除已收藏/关注作者/热点/兴趣；无种子→空；`userId==null`→空；fail-open |
| `RecommendationPipeline` 守护测试 | `cf-recall-ratio=0` 时 CF 不占槽（读路径退化为改造前）；`=0.1` 时 cf 位插入兴趣之后、向量之前、无同页重复 |
| 端到端 | 注册→上传→审核→收藏 A 与 B → 推荐流出现 A/B 的强共现邻居（真 Redis 集群，cf 占比可观测） |
| 回归 | 全引擎 + 全网关套件编译绿；`tf:cf:item:` / `tf:favorite:user:` 清理点检查；跨用例 Redis 污染隔离 |

---

## 7. 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `turbofeed.feed.cf-recall-ratio` | `0.1` | item-CF 占每页槽位比例；`=0` 关闭 |
| `turbofeed.feed.cf.max-seeds` | `50` | 单次收藏关联的其他收藏上限（控制写放大） |
| `turbofeed.feed.cf.max-neighbors` | `500` | 单帖共现邻居上限（ZSET 内存封顶） |
| `turbofeed.feed.cf.favorite-weight` | `1.5` | 单条「同时收藏」边权重（与画像权重 1.5 对齐） |

---

## 8. 改动清单

| 模块 | 文件 | 改动 |
|---|---|---|
| `tf-shared` | `redis/RedisKeys.java` | 新增 `USER_FAVORITE` / `CF_ITEM` 常量及构造方法（跨服务单一真值） |
| `tf-gateway` | 新增 `service/like/ItemCooccurrenceService.java` | 共现矩阵增量（onFavorite/onUnfavorite）+ 批重建（rebuildAll），fail-open |
| `tf-gateway` | `service/like/MediaFavoriteJdbcRepository.java` | 新增 `listAllUserPostPairs`（批重建用） |
| `tf-gateway` | `service/like/MediaFavoriteService.java` | 构造器接入 `ItemCooccurrenceService`；收藏/取消异步块触发共现维护；`USER_FAV_KEY_PREFIX` 迁移到 `RedisKeys.USER_FAVORITE` |
| `tf-feed-engine` | `timeline/RecallType.java` | 新增 `CF` |
| `tf-feed-engine` | `timeline/RecallContext.java` | 新增 `cfKeys` 排除集 |
| `tf-feed-engine` | 新增 `timeline/ItemCfRecallChannel.java` | item-CF 召回通道（PRE_POOL，@Order(3)） |
| `tf-feed-engine` | `timeline/RecommendationPipeline.java` | `cf-recall-ratio` 配置、`slotsFor` 分支、池去重、`cfPart` 装配、审核候选收集 |
| `tf-feed-engine` | `timeline/VectorFeedRecallChannel.java` | 候选收集/part 构建额外排除 `cfKeys` |
| `docs/` | 本设计文档 + changelog `0068` | 随代码同步 |

---

## 9. 明确不做（与做什么同样重要）

1. **不引入新中间件**（Flink/独立图库）——共现就是 Redis ZSET，批重建读现有分片库，零新增基础设施。
2. **不训练 item 塔 / 不进 embedding**——item-CF 是记忆式共现，与双塔/A1 正交；模型化 CF 属后置增强。
3. **不把「完播/点赞」共现并入本期**——只用最强信号「收藏」；后续可把 `interaction_event` 共现并入同一 ZSET。
4. **批重建不挂默认调度**——避免空 cron 启动失败与高频全表扫描；保留为手动/运维触发方法，按需接管理端点。
5. **不抢排序权**——item-CF 只决定「哪类内容进得来」，质量排序统一交 `RankingModel`，与既有通道一致。

---

## 10. 风险与待确认

| 项 | 说明 |
|---|---|
| item-CF 占比取值 | 默认 0.1（与兴趣/向量/关注同级），是否需按 A/B 调整；已做成可灰度 |
| 共现权重口径 | 增量 1.5 / 批重建 1.0 不一致仅在「重建后增量继续叠加」场景下出现，已通过「重建先 DEL 再 ZADD」规避重复计数的主要风险 |
| 种子仅用收藏 | session 序列存的是标签非 postId，本期 CF 种子只用收藏 ZSET；如需「最近看过的」也可作为种子源（后置） |
| 大 V 收藏写放大 | `max-seeds=50` 已封顶；超大收藏量用户共现覆盖度有限，但推荐主流程不受影响 |

---

*注：本文档为「已落地」设计实现方案，配套 changelog `0068`；配置项均可灰度/回滚，`cf-recall-ratio=0` 即完全退化为改造前语义。*
