# 0052 · 抖音式负反馈：不感兴趣 → 对该用户打压同标签内容

日期：2026-09-28　前置：0051（media_tag 冷存）

## 前置：先做了一次「抖音机制落地现状盘点」（含一处认知修正）

用户要求「按抖音推荐机制继续完善代码」。<b>动手前先盘了现有代码</b>——这一步纠出了一个
靠记忆得出的错误结论：

> 我在概念对比里断言「`IMPRESSION` 曝光计数缺失、赛马前提不成立」。<b>这是错的。</b>
> `PostStatService` 早已维护每帖实时分 `{impressions / playCompletes / likes / comments /
> shares / dislikes}`（`tf:post:stat:{timelineKey}` Hash + `HINCRBY`），配 `tf:feed:stat:tracked`
> 待评估集合；`FeedPoolPromoter` 每 30s 扫该集合做晋级/降级。教训：<b>离开代码谈差距，
> 会把已实现的功能当成缺口。</b>

盘点结论（抖音机制里<b>已经落地</b>的部分）：

| 抖音机制 | 本系统对应实现 | 状态 |
|---|---|---|
| 流量池分层赛马 | `FeedPoolPromoter` + `FeedPoolPromoterProperties`（阈值外置、30s 扫描晋级/降级） | ✅ 已有（含对称降级通道） |
| 每帖实时分（曝光/完播/互动） | `PostStatService`（Redis Hash + tracked set） | ✅ 已有 |
| 曝光分层（新内容试水 / 优质占大头） | `readPage` 的 `allocateSlots` + `pool-weights=0.1,0.3,0.6` | ✅ 已有 |
| 完播加权、兴趣加权 | `displayScore`（完播率 + 兴趣匹配，均与 recency 同量纲） | ✅ 已有（0049/0050） |
| 标签兴趣画像与召回 | `TagIndexService` + `InterestService` | ✅ 已有 |
| 冷启动 | L1 小池 10% 槽位 + 画像为空退化为 recency 排序 | ✅ 已有（近似） |
| <b>「不感兴趣」打压相似内容</b> | <b>缺失</b>：`NOT_INTERESTED` 只算全局差评率驱动降级 | ⬅️ 本次补齐 |
| 多路召回（协同过滤/热点/关注） | 无 | 后续 P2 |
| 多层排序（粗排→精排→重排 / 模型） | 单层线性 `displayScore` | 后续 P3 |

## 本次实现：`NOT_INTERESTED` 的专属负反馈通道

语义界定（明确分工，避免两个负向信号互相污染）：

- **`DISLIKE`（踩）** → 作用于<b>单条内容</b>：画像负权重（-1.5）+ 全局流量池降级
  （照 diff 计入 `PostStat.dislikes`）。语义是"这条不好"。
- **`NOT_INTERESTED`（不感兴趣）** → 作用于<b>该用户的相似内容</b>：把目标内容的<b>全部标签</b>
  并入用户级负向标签集 `tf:user:dislike-tags:{userId}`（Redis SET），读路径据此对<b>所有</b>
  命中该标签的内容沉底。语义是"这类我不要"。

<b>为什么用独立 SET 而不是复用画像 Hash 的负权重</b>：画像表达"多喜欢"（权重叠加），
负反馈表达"不要这个标签"（集合判定），消费方不同。混存会让负反馈被同标签其它内容的
高正向权重抵消——用户明明点过不感兴趣，内容仍被捞回来。

<b>为什么减分沉底、而非过滤删除</b>：一次负反馈应显著降低出场概率，而不是物理删除。
内容仍可被他人看到、仍在公域候选里，保留负反馈过期（30d TTL）与纠正后的回旋余地。
惩罚量级 30 天（远大于 recency 差与加权项），保证"明确不要"压过"可能喜欢"；
<b>只作用于池内相对序</b>，不跨池。

改动：
- `InterestService`：`NEG_PREFIX` 常量 + `recordNegativeTags(userId, timelineKey)`
  + `negativeTags(userId)`；`accumulateFromEvent` 为 `NOT_INTERESTED` 开专属分支（HTTP 与
  RocketMQ 两条消费路径共用此方法，故一处接线即可覆盖）。
- `FeedTimelineStore.readPage`：取 `interestService.negativeTags(userId)` 并接入池内重排；
  排序开关加入 `!negative.isEmpty()`（即使完播/兴趣加权被关，负反馈也必须生效——
  用户明确表达不要，优先级高于体验类加权）。
- `FeedTimelineStore`：`NEGATIVE_PENALTY_MILLIS` 常量、`matchesNegative()`（标签相交即命中，
  与正向"累加多标签命中分"对称：正向加分累积，负向一票否决）、`displayScore` 减惩罚项。

## 验证（`target/verify/NegativeFeedbackVerify.java`，真 Redis 集群，10/10 PASS）

无 Spring 容器下用反射注入 `@Value` 配置（与 yml 默认值一致），跑真实
`FeedTimelineStore` / `InterestService` / `TagIndexService`：

- 基线（匿名）：同一池内更新的 java 排在更旧的 cooking 之前（纯 recency 序）；
- <b>核心</b>：该用户对 java 点过「不感兴趣」后，<b>更新的 java 沉到更旧的 cooking 之后</b>
  ——负反馈压过时间序主轴；
- 隔离性：旁观用户 userV、匿名 user 的流完全不受影响（负反馈是用户级的）；
- 分工：`DISLIKE` 不写负向标签集，但仍写画像负权重；
- 幂等与清理：重复执行可清理、可重建。

## 未决（按优先级，承接抖音化路线图）

1. <b>P2 召回多样化</b>：目前仍是「写时物化到分级流量池 + 读时按池权重切片」，
   尚未做到抖音式「全站候选池 + 多路召回（协同过滤 / 热点 / 关注）」。
   多路召回需要 user-item 共现矩阵（behavior 事件已有，需离线建）与热点聚合。
2. <b>P2 更多负反馈信号</b>：当前只有标签级 suppression；抖音还有"屏蔽作者"、
   "重复内容降权"等维度，可按同模式扩展。
3. <b>P3 排序模型化</b>：`displayScore` 仍是单层线性加权（recency + 完播 + 兴趣 - 负反馈惩罚），
   未做粗排/精排分层，也无模型预测（完播率预估等）。

## 备注

- 验证环境：Redis Cluster 3 主 3 从 `192.168.0.6:7000-7005`（本机 Docker）；MySQL 3306 在线、
  3307 副本未起（不影响本改动，负反馈全在 Redis）。
- 全链路 e2e（前端点「不感兴趣」→ 网关上报 → MQ → 引擎 → 下次刷新流变化）未在浏览器验证，
  链路透传路径与 0050 的兴趣项一致（`FeedBehaviorController` / `FeedBehaviorConsumer`
  均调用同一个 `accumulateFromEvent`）。
