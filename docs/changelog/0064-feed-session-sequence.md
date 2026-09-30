# 0064 推荐流精排接入 session 级行为序列（抖音式「刚看过的马上多给」）

> 状态：已落地 · 已单测 · 已真实启动验证 · 已实时落库验证
> 关联：0061 长期画像 / 0062 长短期双层 / 0063 短期层进精排

## 一、为什么需要 session 序列（长期/短期画像不够）

0061/0062 的画像是对用户兴趣的**聚合估计**，丢掉了「顺序」与「最近性」——
它知道你长期喜欢钓鱼、最近在追钓鱼，但不知道你是「**刚连着看了 5 条钓鱼**」。

序列建模要捕捉的正是这层**局部、强时效**的信号：一条内容的标签若与你
**最近互动过**的内容高度重合，即使长期画像里它权重不高，此刻也该排更前
（target-attention 的最简形态）。这是抖音「跟手感」的来源。

## 二、设计

- **存储**：每用户一个 Redis LIST `tf:user:session:{userId}`，最新互动在表头。
  每条记录 = `epochMillis|weight|tag1,tag2,...`（纯文本，标签已 sanitize `|`/`,`，
  避免 JSON 解析依赖）。写时 `LPUSH + LTRIM(max)` 保窗口、`EXPIRE` 兜底；读时
  `LRANGE` 取最近 N 条，按时间距算 recency 权重（半衰期可配）。用 LIST 而非 Hash，
  是为天然有序 + 定长裁剪。

- **写**（`SessionSequenceService.record(userId, timelineKey, weight)`）：
  仅 `weight > 0`（正向外互动 LIKE/COMMENT/SHARE）且 `tagsOf(timelineKey)` 非空才
  `LPUSH`。`weight` 口径复用 `InterestService.eventWeight`，与画像一致。fail-open：
  Redis 异常只告警不抛，序列缺失最多让「跟手感」变弱，不阻断浏览。

- **读**（`SessionSequenceService.recent(userId, n)` → `SessionItem(tags, recencyWeight)`）：
  单条 recency = `0.5^(距现在毫秒 / 半衰期毫秒)`，半衰期默认 30 分钟。

- **与画像的关系**：画像答「你是什么样的人」，session 答「你此刻在做什么」。
  二者在 `LinearWeightedRankingModel` 叠加——画像给稳定底子，序列给跟手尖峰。
  `sessionMatch` 与目标候选的标签重合度（重合数 / 候选标签数 × recency）累加得到。

- **可降级**：`turbofeed.feed.session.enabled=false` 后退化为「无 session 信号」，
  精排回到纯画像+统计，语义不破。三路（长期/短期/session）各自可独立关闭。

## 三、配置（application.yml，均带默认值）

```yaml
turbofeed:
  feed:
    session:
      enabled: true          # 关闭后退化无 session 信号
      max: 30                 # 序列窗口保留的最大互动条数
      ttl-minutes: 120        # 整份序列 TTL（用户停手这么久即清掉）
      half-life-minutes: 30   # 序列内单条互动的 recency 半衰期
    rank:
      session-weight: 0.5     # session 序列匹配权重（调大=更强的即时跟随）
      session-score-cap: 3.0  # session 序列匹配分上限
```

## 四、改动文件

| 文件 | 改动 |
| --- | --- |
| `interest/SessionSequenceService.java` | **新增**：session 序列读写（LPUSH/LRANGE + 半衰期衰减 + fail-open） |
| `interest/InterestService.java` | 新增 `eventWeight(FeedBehaviorEvent)`：对外复用权重口径，供 session 决定「记不记、记多重」 |
| `ranking/RankingFeatures.java` | 新增 `sessionMatch` 字段 |
| `ranking/RankingProperties.java` | 新增 `sessionWeight` / `sessionScoreCap` |
| `ranking/LinearWeightedRankingModel.java` | 精排总分叠加 `sessionWeight * sessionInterest` |
| `timeline/FeedTimelineStore.java` | 注入 `SessionSequenceService`，`readPage` 读最近序列并算 `sessionMatch` 传入 RankingFeatures |
| `timeline/FeedTimelineController.java` | `behavior` 路径在累积画像后同步 `record` session（与画像共用权重口径） |
| `timeline/FeedBehaviorConsumer.java` | MQ 消费路径同步 `record` session（与 HTTP 兜底路径成对修改） |
| `src/main/resources/application.yml` | 新增 `session` 与 `rank.session-*` 配置 |

> ⚠️ `FeedTimelineController.behavior` 与 `FeedBehaviorConsumer` 必须**成对修改**：
> 两条埋点路径最终都落到「统计 + 画像 + session 序列」三件事，任一侧漏改会造成
> 埋点路径不一致（默认环境有数据、开了 MQ 反而没样本）。

## 五、验证

- **单测** `SessionSequenceRankingVerify` **4/4**：session 提升排序；`sessionWeight=0`
  退化为无 session；纯 session 胜纯弱长期；负向（DISLIKE）沉底。
- **引擎真实启动（cluster 模式）**：`Started FeedEngineApplication in 6.8s`，0 Redis ERROR。
- **实时落库（决定性的端到端证据）**：经引擎 HTTP 接口
  `append`（内容带 `tags=[java,测试]`）→ `behavior`（`LIKE`，`userId` 显式）→ 直连集群读取：
  - `tf:user:session:{userId}` → `SIZE=1`，条目 `1790735623080|1.0|java,测试`
    （epoch|weight|tags，正是设计格式；证明 `record` 经 `tagsOf` 反查标签后 `LPUSH` 真实落地）；
  - `tf:media:tags:{timelineKey}` → `[java, 测试]`（append 的标签索引已写入）。

## 六、环境注意事项（非代码改动）

本次验证发现并修正了一个**开发环境坑**：此前运行中的引擎以 `single` 模式指向已下线的
`localhost:6379`，而 6379 实际未启动，导致**所有 Redis 写入（时间线、标签索引、画像、
session 序列）全部 fail-open 静默丢弃**——引擎日志持续刷
`Unable to connect to Redis`，但 HTTP 接口仍返回 `code:0`，极具迷惑性。

网关此前已正确接集群（`192.168.0.6:7000–7005`），但引擎未接。验证前已将引擎改为
**cluster 模式**（与网关同拓扑、同密码）重启，才观测到真实写入。本地启动引擎请使用
cluster 配置（`target/verify/run_engine_cluster.sh`），否则推荐流相关写入不会落库。
