# 用户收藏功能设计与实现方案（进画像 → 影响推荐）

> 状态：**设计稿（未实施）**——本文档是完整设计实现方案，含数据模型、接口、画像/推荐联动、降级与验证，
> 不含已落地代码。落地时按仓库约定同一提交内同步模块文档并追加编号 changelog。
> 前置阅读：`docs/architecture/recommendation-design.md`（推荐蓝图）、changelog `0057/0059/0060/0061/0062/0063/0064`
> （已落地的画像与推荐链路）。

---

## 0. 结论先行

1. **收藏 = 用户显式、强正向、可持久回看的意图信号**，信息量高于点赞/完播。它对画像的贡献不应与点赞等同，
   应作为一个**独立且更高权重**的正样本进入现有画像体系（长期层 + 短期层 + session 序列），从而自然改变召回与排序。
2. **收藏本体与「进画像」是两件事，分开设计、互不阻塞**：本体（收藏/取消/我的列表）是事务型能力，
   完全复用 `MediaLikeService` 的成熟模式（Redis 权威 + 异步落库 + 去重 + 灾备重建 + 限流 + fail-open）；
   进画像是在 `InterestService` 权重表加一行 + 埋点类型扩展，改动极小。
3. **取消收藏不进负向**（对齐 `UNLIKE` 语义：取消只是手滑收回，不是"别再给我看"），负向仍只由
   `DISLIKE / NOT_INTERESTED` 表达——这条边界不模糊。
4. **推荐联动**：收藏标签自动进「召回选标签 + 排序加权」；已收藏内容在推荐流做**排除/降权**；
   收藏列表可作为一路独立的**召回候选源**（「你收藏过这类，同类多给」）。
5. **零新中间件、零新增分片复杂度**：收藏表按 `user_id` 分片（`hashMod128`，与 `user_follow/user_fans` 同策略），
   「我的收藏列表」单分片命中；不做跨分片聚合。
6. **全链路 fail-open**：收藏失败不影响浏览；画像失败只弱化个性化；推荐侧缺失信号自动回退。

---

## 1. 需求与边界

### 1.1 功能定义

| 能力 | 说明 | 是否本期 |
|---|---|---|
| 收藏 / 取消收藏 | 对一条帖子做 toggle（幂等），记录"我的收藏" | ✅ P0 |
| 我的收藏列表 | 分页、时间倒序，展示已收藏内容（整帖/卡片） | ✅ P0 |
| 已收藏态展示 | 列表/详情页展示红心/星标态，批量 O(1) 判定 | ✅ P0 |
| 收藏进画像 | 收藏作为强正样本进长期+短期+session | ✅ P0 |
| 推荐联动（召回/排序/去重） | 见 §5 | ✅ P1 |
| 收藏数（内容维计数/热度） | 某内容被多少人收藏，可选进热度分 | ⏸ P2（产品确认后） |
| 收藏夹 / 分组 / 跨端同步 | 多分类管理 | ⏸ 后置 |

### 1.2 收藏与现有信号的语义边界（最关键的定位）

| 信号 | 强度 | 语义 | 进画像？ | 取消化？ |
|---|---|---|---|---|
| 点赞 `LIKE` | +1.0 | 随手认可 | ✅ 长期+短期+session | 取消 `UNLIKE` **不进**（手滑） |
| 评论 `COMMENT` | +1.5 | 参与讨论 | ✅ | — |
| 分享 `SHARE` | +1.5 | 主动传播 | ✅ | — |
| 完播 `WATCH/PLAY_COMPLETE` | 0.1/0.3 | 被动看完 | ✅ | — |
| **收藏 `FAVORITE`** | **+2.0（建议）** | **"我想再看/反复看"，显式持久意图** | ✅ **长期+短期+session** | 取消 `UNFAVORITE` **不进**（手滑） |
| 搜索 `search` | +0.2 | 显式查询意图 | ✅ | — |
| 不感兴趣 `NOT_INTERESTED` | 负向集 | "别再推这类" | ✅ 标签级压制 | — |
| 讨厌 `DISLIKE` | -1.5 | 单条负向 | ✅ | — |

**为什么收藏权重设 2.0（高于 LIKE、与 COMMENT/SHARE 同级或略高）**：

- 点赞可随手、评论有门槛、分享要动手；收藏是"用户愿意把它留存在自己空间里反复看"的**持久显式意图**，
  且通常发生在完播/认可之后，是漏斗最末端、最可信的正样本。
- 但权重不宜设得太高（避免几条收藏就把长期画像锁死），2.0 是个"显著高于点赞但仍在可控范围"的折中。
- **可配置**：`turbofeed.feed.interest.favorite-weight`（默认 2.0），与其它权重同级治理，可灰度可回滚。

**为什么取消收藏不进负向**：与 `UNLIKE` 同理由——"我暂时收回了"不等于"别再给我推"。
若把取收藏记成负样本，会与「不感兴趣」语义混淆（后者是标签级压制）。**负向只由显式负反馈提供**。

---

## 2. 现状梳理（画像与推荐链路，改造的底座）

### 2.1 行为链路（前端 → 网关 → 引擎）

```
turbo-feed-ui 埋点(攒批2s/50条, keepalive)
  │ POST /api/v1/feed/behavior (FeedBehaviorController.report, 权限 FEED_INTERACT)
  ▼
网关：
  - LIKE 单独走 MediaLikeService（去重权威 + 计数 + 异步落库 + 发 LIKE/UNLIKE 事件给引擎）
  - 其余类型(IMPRESSION/WATCH/COMMENT/SHARE/DISLIKE...)整批 → publisher.report → 引擎
  - 每条正向互动 → reviewService.onInteraction（热度复审旁路）
  - 非曝光 → interactionEventService.record → interaction_event 表（持久训练样本）
  ▼
引擎 (FeedTimelineController.behavior / FeedBehaviorConsumer, 两路成对):
  - interestService.accumulateFromEvent  → 画像（长期+短期+负反馈）
  - sessionSequenceService.record        → session 序列（仅 weight>0 的正互动）
  - behaviorLogSink.log                  → 训练样本明细（M0，默认关闭）
```

### 2.2 画像体系（引擎 `InterestService`，已落地）

| 层 | Redis Key | 半衰期 | TTL | 用途 |
|---|---|---|---|---|
| 长期层 | `tf:user:interest:{uid}` | 14 天 | 30 天 | "你是什么样的人"，供**排序加权** |
| 短期层 | `tf:user:interest:recent:{uid}` | 12 小时 | 7 天 | "你现在想要什么"，供**召回选标签** |
| 负反馈集 | `tf:user:dislike-tags:{uid}` | — | 30 天 | 标签级压制（`NOT_INTERESTED`） |
| session 序列 | `tf:user:session:{uid}` | 单条 30min | 120min | "此刻在做什么"，精排叠加 |

- **权重表** `WEIGHTS`：`LIKE=1.0, COMMENT=1.5, SHARE=1.5, DISLIKE=-1.5`；`WATCH/PLAY_COMPLETE` 分段；
  搜索词 0.2 独立入画像。
- **召回**：`recallTags` 归一化融合（`long*1.0 + short*2.0`）→ 选 TopN 标签 → `recallTimelineKeys`
  按标签轮转取候选（负反馈标签直接不进候选）。
- **排序**：`LinearWeightedRankingModel` 叠加「长期权重 + 内容统计 + session 匹配」。
- **session 写入**：`FeedTimelineController.behavior` 里 `sessionSequenceService.record(e.userId(), ...,
  interestService.eventWeight(e))` ——**只要某行为 `eventWeight>0` 就自动进 session**，收藏无需额外改这里。

### 2.3 可复用的"事务型互动"模式（`MediaLikeService`）

点赞是收藏最接近的参照系，整套模式可镜像：

- **去重权威 + 已收藏态**：Redis Set，热键按内容分 64 槽（`tf:like:users:{postId}:{slot}`）；
  读按内容聚合（`SCARD`）、按用户判定（`SISMEMBER`）。
- **计数 Key**：`tf:like:count:{postId}`（单 Key，`INCR/DECR`，冷启动 64 槽对账）。
- **异步落库**：`interactionExecutor`（队列 1024 + CallerRunsPolicy）异步刷 `media_like` 灾备表，主链路不阻塞 DB。
- **behavior 事件**：实际状态变化才发 `LIKE/UNLIKE` 给引擎（去重保证「一次状态变化 = 一条事件」）。
- **限流**：`UploadRateLimiter` 前缀 `rl:like:` 单用户固定窗口。
- **灾备重建**：`rebuildFromDb` / `recountFromSlots` 供管理端触发。
- **fail-open**：全链路兜底，绝不把"点个赞"的失败抛给用户。

### 2.4 分表与持久行为表

- `media_like`：分片键 `post_id`，`hashMod128`（读"某内容所有点赞"单分片命中）→ 收藏本体读是"我的列表"，故**按 `user_id` 分片**。
- `user_follow/user_fans`：分片键 `user_id`，`hashMod128` → 收藏表对齐这个。
- `interaction_event`：单表（`ds_0`），持久训练样本来源（`InteractionEventRow`：`eventType` 为
  `IMPRESSION/WATCH/PLAY_COMPLETE/LIKE/COMMENT/SHARE/DISLIKE`）→ 需扩展支持 `FAVORITE`。

---

## 3. 数据模型设计

### 3.1 持久化表 `favorite`（分片键 `user_id`，`hashMod128`）

```sql
CREATE TABLE `turbo_feed_1`.`favorite_0` (
  `id`         BIGINT       NOT NULL                COMMENT '雪花ID, 主键',
  `user_id`    BIGINT       NOT NULL                COMMENT '收藏者用户ID, 分片键',
  `post_id`    VARCHAR(255) NOT NULL                COMMENT '被收藏内容ID(=时间线幂等键)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_post` (`user_id`, `post_id`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='favorite(物理分片 turbo_feed_1.favorite_0)';
```

- **分片键 `user_id`**：核心读是"我的收藏列表"，`WHERE user_id=? ORDER BY created_at DESC` 单分片命中，
  零 fan-out。`uk_user_post` 提供 DB 侧幂等兜底（与 Redis 去重权威形成"最终一致"备份）。
- 物理表 `favorite_0..127`（偶数落 `ds_0`、奇数落 `ds_1`），由 SS `autoTables` 推导，与 `user_follow` 同策略。
- 新建逻辑表需在 `shardingsphere-config-128.yaml` 增加 `favorite` autoTable（`shardingColumn: user_id`，
  `shardingAlgorithmName: hashMod128`），并写 `deploy/mysql/init-local.sql` + `migrate_*.sql`。

### 3.2 Redis 在线存储（收藏本体，读优先"我的列表"）

| Key | 结构 | 语义 |
|---|---|---|
| `tf:fav:{userId}` | **ZSET**（member=`postId`，score=收藏 epoch ms） | 去重权威 + 我的收藏列表（`ZSCORE` 判态 O(1)、`ZRANGEBYSCORE` 分页、`ZADD` 幂等） |
| `tf:fav:count:{postId}`（可选 P2） | String | 内容维收藏数（`INCR/DECR`），进热度分/质量信号 |

**为什么收藏用 ZSET 而点赞用 Set**：点赞的读主要是"某内容被多少/哪些人赞"（**内容维**，故按 postId 分槽聚合）；
收藏的读主要是"我收藏了哪些内容、要分页翻"（**用户维**）。按用户自然隔离，不存在单 Key 元素膨胀
（单用户收藏量有限），**不需要内容维分槽**；ZSET 天然有序、可分页，一次 `ZADD` 即幂等。

**TTL / 滑动续期**：收藏是"想长期回看"，列表不应随意过期 → 对齐点赞 A4 做法：写时 `ZADD + EXPIRE`，
读路径（收藏列表页）`EXPIRE` 续期。收藏列表 Key 若被 Redis 重建清掉，由 DB 灾备重建兜底。

**计数 Key（P2 可选）**：仅当产品要展示"多少人收藏"或把收藏数计入热度时引入，`INCR/DECR` 与列表同步
（对齐 `bumpCount` 的冷启动对账）。本期默认不做，避免过早承担内容维热键。

### 3.3 行为契约扩展

- `FeedBehaviorEvent.type` 新增取值 **`FAVORITE`**（收藏）/ **`UNFAVORITE`**（取消收藏）——仅新增枚举值，
  **向后兼容**（旧端不识别也不影响），`schemaVersion` **不递增**（非破坏性变更）。
- `InteractionEventRow.eventType` 同步支持 `FAVORITE`（供训练样本）。
- `BehaviorLogSink` 落盘明细自动携带（一行一事件，无需新增字段）。

---

## 4. 接口设计

### 4.1 网关对外 API（权限 `FEED_INTERACT`，登录态）

| 方法/路径 | 说明 | 语义 |
|---|---|---|
| `POST /api/v1/feed/media/favorite` body `{postId, favorite:true/false}` | 收藏 toggle（对齐 `/media/like`） | 返回操作后"是否已收藏"态；幂等 |
| `GET /api/v1/feed/favorites?page=&size=` | 我的收藏列表（时间倒序分页） | 返回 `FeedItemView` 列表 + 已收藏态 |
| 列表/详情页 | 批量已收藏态 `enrich` | 一次 `ZSCORE`/pipeline 取 N 条（对齐 `isLikedBatch`） |

- 专用 toggle 接口是**主路径**（收藏有持久列表语义，不宜混进批量 behavior 上报为主）；
  同时在 `FeedBehaviorController.report` 里把 `FAVORITE` 像 `LIKE` 一样**单独路由到 `FavoriteService`**
  （保证"一次实际状态变化 = 一条引擎事件"，去重防双写），其余类型照旧整批投递引擎。

### 4.2 引擎侧（内部）

- `FeedTimelineController.behavior` / `FeedBehaviorConsumer`：`accumulateFromEvent` 与
  `sessionSequenceService.record` 无需改动——`FAVORITE` 的权重 >0 即自动进画像与 session；
  `UNFAVORITE` 权重 0 自动跳过（见 §5）。
- **推荐读路径去重**（P1）：`FeedTimelineStore.readPage` 对用户最近 N 个已收藏 `postId` 做排除/降权
（对齐已曝光过滤 `tf:feed:seen:{uid}` 的手法）。

### 4.3 前端（turbo-feed-ui）

- 列表/详情卡片加收藏按钮（星标/红心），点击调 `POST /favorite`，同时走埋点上报 `FAVORITE`（**必须接埋点**，
  否则 0057 已证实的"服务端管线空转"会重演——画像只有真实上报才有输入）。
- 新增「我的收藏」入口页（复用公域 Feed 卡片渲染，带分页）。

---

## 5. 画像与推荐联动设计（本方案核心）

### 5.1 收藏进画像：改动点（极小）

在 `InterestService.WEIGHTS` 增加 `"FAVORITE", 2.0`；新增可配置 `favorite-weight`（默认 2.0）；
`UNFAVORITE` 不在权重表 → `weightOf` 返回 0 → 不进画像、不进 session（对齐 `UNLIKE`）。

| 消费方 | 收藏如何参与 | 是否需改代码 |
|---|---|---|
| 长期层 `tf:user:interest:{uid}` | `FAVORITE` 以 +2.0 累积，慢衰减 → "你是长期喜欢这类的人" | 仅权重表加行 |
| 短期层 `tf:user:interest:recent:{uid}` | 同权重快衰减 → "你最近在收藏这类" | 仅权重表加行（`accumulateFromEvent` 已双写） |
| session 序列 | `eventWeight(FAVORITE)=2.0>0` → 自动 `LPUSH` → 精排叠加"跟手感" | **零改动**（复用 0064） |
| 召回 `recallTags` | 收藏标签进融合 TopN → 抢召回位 | 零改动 |
| 排序 `weightedTags` | 收藏标签进长期层排序加权 | 零改动 |

### 5.2 推荐读路径联动（P1）

| 手段 | 目的 | 落地 |
|---|---|---|
| **已收藏排除/降权** | 用户已收藏的内容，默认不必在推荐流反复出现 | `readPage` 读最近 N 个收藏 postId，命中则过滤/降权（对齐已曝光过滤） |
| **收藏同类召回**（可选） | "你收藏过这类，同类多给"——收藏是强冷启动信号 | 把收藏标签并入 `recallTimelineKeys` 的标签源（收藏多的冷启动用户尤其受益） |
| **热度/质量信号**（P2 可选） | 收藏数作为强正反馈进内容质量分 | `PostStatService` 增加 `FAVORITE` 权重；需内容维计数 Key |

### 5.3 训练样本（P3 前置）

- `FAVORITE` 作为 **y=1 正样本**进 `interaction_event`（`InteractionEventService.record`）与
  `BehaviorLogSink`（M0 明细落盘）——离线双塔/精排（P3）的"强正样本"来源，与点赞/完播并列为高质量正样本。
- `UNFAVORITE` **不落为正/负样本**（对齐不报取消点赞的既有决策，见 0057）。

### 5.4 一致性说明

- **画像新鲜度**：收藏进画像走 Redis，秒级生效；画像失败 fail-open → 个性化弱化，不阻断浏览。
- **收藏列表权威**：Redis ZSET 是读权威，DB 是灾备/对账基准；`rebuildFromDb` 可从 `favorite` 表重建列表与去重态。

---

## 6. 降级与一致性（对齐点赞的成熟套路）

| 风险/场景 | 对策 |
|---|---|
| Redis 异常 | fail-open：收藏按请求态兜底、列表返回空，浏览不阻断 |
| Redis 重建丢列表 | `FavoriteService.rebuildFromDb`（按 `favorite` 表重建 ZSET）+ 管理端入口 |
| 异步落库与 Redis 最终一致 | 极端竞态下可能孤儿备份行，因 Redis 是权威，对用户不可见（同点赞） |
| 收藏刷量/滥用 | 单用户限流 `rl:fav:`（复用 `UploadRateLimiter`），Sentinel 机器维限流 |
| 画像被收藏噪声主导 | 权重可配 + 半衰期衰减（收藏只影响召回位与排序，不锁死） |
| 新增 Redis key 清理遗漏 | 新增 `tf:fav:*` 后**全局搜所有清理点**成对补齐（0062 已实证这是双写/新 key 的必然成本） |
| 合规 | 收藏列表最小化、支持按用户删除（DB 删行 + Redis 删 key + 画像按 0062 的删除语义） |

---

## 7. 验证方案（遵循仓库"真启动/真 Redis/端到端"标准）

| 层 | 断言 |
|---|---|
| FavoriteService 单测 | toggle 幂等（重复收藏不重复计数）、取消幂等、已收藏态 O(1)、列表分页时间倒序、限流、`rebuildFromDb` |
| 画像单测 | `FAVORITE` 同权重进长期+短期；`UNFAVORITE` **不进**画像/session；`favorite-weight=0` 可关闭；权重可配 |
| 召回/排序单测 | 收藏标签提升召回位；已收藏内容被推荐流排除；session 出现收藏（`LPUSH` 落地，`epoch|weight|postId|tags`） |
| 端到端 | 注册→上传→审核→推荐可见→收藏→我的收藏列表→画像/推荐变化可观测（真 Redis 集群） |
| 回归 | `run_all_feed.sh` 全绿；新增 `tf:fav:*` key 的清理点成对补齐；跨用例污染隔离（沿用 `reset_feed_redis.sh`） |

---

## 8. 改动清单（与仓库 changelog 风格对齐）

| 模块 | 文件 | 改动 |
|---|---|---|
| `tf-shared` | `FeedBehaviorEvent` | 文档补充 `FAVORITE/UNFAVORITE` 类型（契约向后兼容） |
| `tf-gateway` | 新增 `service/favorite/FavoriteService.java` | 镜像 `MediaLikeService`：ZSET 去重/列表 + 计数 + 异步落库 + 限流 + 重建 + fail-open |
| `tf-gateway` | 新增 `service/favorite/FavoriteJdbcRepository.java` | `favorite` 表读写 |
| `tf-gateway` | 新增 `controller/FavoriteController.java` | `POST /media/favorite` + `GET /favorites` |
| `tf-gateway` | `controller/FeedBehaviorController.java` | `FAVORITE` 单独路由到 `FavoriteService`（对齐 LIKE） |
| `tf-gateway` | `service/feed/InteractionEventService` / `InteractionEventRow` | `eventType` 支持 `FAVORITE` |
| `tf-gateway` | `resources/shardingsphere-config-128.yaml` | 新增 `favorite` autoTable（`user_id` hashMod128） |
| `deploy/mysql` | `init-local.sql` + `migrate_*.sql` | `favorite_0..127` DDL |
| `tf-feed-engine` | `interest/InterestService.java` | `WEIGHTS` 加 `FAVORITE=2.0` + `favorite-weight` 配置；`UNFAVORITE` 返回 0 |
| `tf-feed-engine` | `timeline/FeedTimelineStore.java`（P1） | 推荐读路径排除/降权已收藏 postId |
| `tf-feed-engine` | `resources/application.yml` | `turbofeed.feed.interest.favorite-weight:2.0` |
| `turbo-feed-ui` | `feed.html` / 收藏页 | 收藏按钮 + 我的收藏入口 + `FAVORITE` 埋点 |
| `docs/` | changelog + 模块文档 | 随代码同步，追加编号 changelog |

---

## 9. 分期落地

| 期 | 内容 | 新增 | 验收（能回答的业务问题） |
|---|---|---|---|
| **P0** | 收藏本体（表/接口/列表/已收藏态）+ 进画像（权重+session） | 无新组件 | "用户能收藏并回看；收藏是否改变了召回与排序？" |
| **P1** | 推荐读路径排除已收藏 + 收藏同类召回 + `FAVORITE` 进训练样本 | 无新组件 | "个性化流里已收藏内容不再反复出现；收藏同类是否提升互动？"（A/B） |
| **P2（可选）** | 内容维收藏计数 + 收藏数进热度 + 收藏夹/分组 | 无新组件 | "收藏数能否作为内容质量信号提升热门流质量？" |

---

## 10. 明确不做（与做什么同样重要）

1. **不做内容维收藏计数/热度**（P0/P1）——产品未明确，避免提前承担内容维热键与跨分片聚合。
2. **取消收藏不进负向、不进样本**——与 `UNLIKE` 语义一致，负向只由显式负反馈表达。
3. **不在分片库上为收藏列表做跨分片聚合**——按 `user_id` 分片，单分片命中，不 fan-out。
4. **不引入新中间件**（Flink/向量库/独立特征库）——收藏接入现有 Redis + MySQL + 画像链路即够。
5. **收藏列表不做强一致**——Redis 权威 + DB 灾备，最终一致，降级为 fail-open。

---

## 11. 风险与待确认

| 项 | 说明 |
|---|---|
| 收藏权重取值 | 建议 2.0，是否与产品/运营对齐（是否高于分享）需确认；已做成可配置 |
| 收藏数是否进热度 | 需产品确认是否展示"多少人收藏"、是否参与热门/质量分 |
| 收藏列表是否要"取消后置顶/排序" | 默认按收藏时间倒序；如需"最近交互优先"可扩展 |
| 与"我的喜欢/点赞列表"是否合并 | 默认独立入口；产品确认是否需要"收藏=点赞+收藏"聚合视图 |

---

*注：本文档为设计实现方案，落地前请对"收藏权重""是否进热度""收藏列表排序"等产品口径做一次确认；其余默认值与仓库现有语义一致。*
