# 0046 · Feed 流量池赛马（抖音式 P1）

> 接续 [0045](./0045-feed-visibility-p0.md) 的「写时物化止血」之后，本变更把公域分发从
> "按信用等级一次性定池、此后不变"升级为 **抖音式流量池赛马**：行为埋点 → 内容实时分 →
> 池间晋级器，让优质内容凭互动率从低池被放大到高池，新内容先拿少量曝光试水。

## 背景 / 现状缺口

- 原 `poolLevel` 在**发布时**由 `CreditLevel#poolLevel()` 一次性决定（`L0=0 / L1=1 / L2=3`），
  此后**不变**（见 `docs/architecture/moderation-design.md` "现状"段）。
- `FeedTimelineStore.readPage` 把 L1..L3 三个池**等权合并**——也就是说"流量池"在读取端是个
  **纯标签，不产生任何曝光差异**。内容进哪个池都不影响它在发现流里的占比，赛马无从谈起。
- 没有内容维度的互动数据：系统知道"谁发了、信用几级"，但不知道"这条内容用户喜不喜欢"。

## 设计

三个支柱，全部**复用现有 Redis，不引新存储**（用户硬性约束）：

### 1. 行为埋点 → 内容实时分
- 每帖一个 Redis Hash：`tf:post:stat:{timelineKey}`，字段
  `impressions / playCompletes / likes / comments / shares / dislikes`。
- 事件类型：`IMPRESSION`（曝光，前端渲染进视口）/ `PLAY_COMPLETE`（完播）/
  `LIKE` / `COMMENT` / `SHARE` / `DISLIKE`（不感兴趣）。
- 入口：网关 `POST /api/feed/behavior`（新增）→ `BehaviorEventPublisher`
  （`turbofeed.mq.enabled=false` 走 HTTP 兜底 / `=true` 走 RocketMQ，与 timeline 发布器同构）
  → 引擎 `POST /internal/feed/behavior` / MQ 消费者 `FeedBehaviorConsumer`。
- 统计用 `HINCRBY` 原子自增（高频小写入，最契合 Hash）；统计丢失只告警，绝不影响主流程。

### 2. 待评估集合（避免 SCAN）
- 行为事件触发时把 `timelineKey` 加入 `tf:feed:stat:tracked`（有界 Set）。
- 晋级器只扫这个集合，**不**对 `tf:post:stat:*` 做 SCAN（避免大 keyspace 扫描抖动）。

### 3. 池间晋级器（核心）
- `FeedPoolPromoter`：`@Scheduled(fixedDelay=30s)` 扫待评估集合，按互动率把内容
  **仅升不降**地从低池搬到高池；`scanNow()` 同实现，供运维/测试手动触发。
- 晋级判定（演示默认，可外置）：
  - 曝光 `< 20`（样本不足）不晋级；
  - 差评率（`dislikes/impressions`）`≥ 30%` → 锁在/打回 L1，不给公域放大；
  - L1 且互动率 `≥ 5%` → L2；L2 且互动率 `≥ 8%` → L3。
  - 互动率 = `(点赞+评论+分享 + 0.5×完播) / 曝光`（完播权重低于主动互动）。
- **晋级复用现有 ZSET + 反查索引，不引新存储**：`FeedTimelineStore#promote(timelineKey, targetPool)`
  先按反查索引定位原桶与成员串 → `ZSCORE` 取回原始 score（入流时刻）→ `ZREM` 旧桶 +
  `ZADD` 新池桶（同日期、同 score，仅池号 +1）→ 把反查索引改写成新桶位置。
  下架走 `remove` 时据新索引精确摘除，不漏。

### 4. 让晋级"可见"：读路径曝光加权（抖音味的关键）
- `readPage` 改为**按池权重分配每页槽位**：默认 `L1=10% / L2=30% / L3=60%`，
  高池（已验证的优质内容）排前面占大头，低池（新内容试水）占小头；分页时每池各自推进游标。
- 这正是抖音"赛马"的体感：新内容先拿少量曝光，互动率达标才被晋级到更大池放大。
- `pool-read-weight-enabled=false` 退化为"全池合并 + 按入流时刻全局倒序"（改造前语义，演示/回滚用）。

## 改动清单

**引擎（tf-feed-engine）**
- `FeedTimelineStore`
  - `MAX_POOL_LEVELS` 改为包可见（晋级器需读）。
  - 新增 `@Value`：`turbofeed.feed.pool-read-weight-enabled`（默认 true）、
    `turbofeed.feed.pool-weights`（默认 `0.1,0.3,0.6`）。
  - `readPage` 重写为池加权版（保留等权回退分支 + helper `parsePoolWeights`/`allocateSlots`/`parseMember`）。
  - 新增 `promote(timelineKey, targetPool)` 与 `currentPool(timelineKey)`（复用 ZSET + 反查索引）。
- `FeedEngineApplication`：加 `@EnableScheduling`（晋级器定时任务需它）。
- `PostStatService`（新）：实时分 Hash + tracked set。
- `FeedPoolPromoter`（新）：定时/手动晋级器。
- `PostStatController`（新）：`POST /internal/feed/behavior`、`POST /internal/feed/pool/promote-scan`、
  `GET /internal/feed/pool/debug`（调试）。
- `FeedBehaviorConsumer`（新）：RocketMQ 消费（@ConditionalOnProperty mq.enabled=true，并发消费）。

**契约（tf-shared）**
- `FeedBehaviorEvent`（新 record）：行为事件载体（零 Jackson 注解，靠 -parameters 反序列化）。

**网关（tf-gateway）**
- `FeedEngineClient#reportBehavior(List<FeedBehaviorEvent>)`（fail-open）。
- `BehaviorReport`（新 DTO）、`BehaviorEventPublisher`（新接口）、
  `HttpBehaviorEventPublisher` / `RocketMqBehaviorEventPublisher`（按 mq.enabled 切换）。
- `FeedBehaviorController`（新）：`POST /api/feed/behavior`。
- `Permission.FEED_INTERACT`（新，授予 `Role.USER`）——任何登录用户可上报互动。

## 验证（实机，引擎 8083 + Redis 集群 7000-7002）

构造 p1(L1) / p2(L2) / p3(L3) 三条内容：

1. 推荐流（加权）顺序 = `['p3','p2','p1']` —— **高池优先**，证明读路径曝光加权生效。
2. 对 p1 灌行为：20 曝光 + 5 赞 + 3 评 + 2 转 + 10 完播 → 实时分写入（`tf:post:stat:p1`）。
3. `POST /internal/feed/pool/promote-scan` → 返回 `1`（晋级 1 条）。
4. `GET /internal/feed/pool/debug?timelineKey=p1`：晋级前 `pool=1`、晋级后 **`pool=2`**
   （互动率 `(5+3+2+0.5×10)/20 = 0.75 ≥ 0.05` → L1→L2，符合阈值）。
5. 晋级后推荐流 p1 已**高于**其余 L1 内容（升了一级曝光池），证明池真正控制曝光。

两模块 `mvn compile` 通过（含 `-Dmaven.resources.skip=true` 沙箱参数）。

## 配置旋钮
- `turbofeed.feed.pool-weights`（默认 `0.1,0.3,0.6`，按池序 L1..Ln）
- `turbofeed.feed.pool-read-weight-enabled`（默认 true）
- `turbofeed.feed.promoter-interval-ms`（默认 30000）
- `turbofeed.feed.behavior.topic` / `.consumer-group`（默认 `turbofeed-feed-behavior`）
- 晋级阈值（MIN_IMPRESSIONS / 两档互动率 / 差评率 / 完播权重）当前为 `FeedPoolPromoter` 常量，后续应外置。

## 遗留 / 下一步
- **前端埋点接线**：后端接口与发布器已就绪，但 `turbo-feed-ui` 尚未在渲染/点赞/评论/分享时调用
  `/api/feed/behavior`（曝光需 IntersectionObserver / 视口进入；互动绑定既有按钮）。这是 P1 真正"端到端闭环"的最后一块。
- **实时晋升**：当前 30s 周期扫描；互动率可改为"事件触发即时判定"（更高实时性，代价是每次事件做 ZREM+ZADD）。
- **MQ 路径 E2E**：`turbofeed.mq.enabled=true` 时走 RocketMQ 的完整链路未在本沙箱跑（无 name-server），
  仅编译验证；需有 name-server 后回归。
- **阈值调参 / 回退 L1 的"临时"语义**：当前差评率过高只"锁在 L1"不主动打回（因 promote 仅升不降）；
  若要支持"高热差评降级"，需放开 promote 的单向约束并加防抖。
- **跨池去重**：晋级只改池号不改 score，故同一帖不会在多个池重复出现（反查索引唯一），无需额外去重。
