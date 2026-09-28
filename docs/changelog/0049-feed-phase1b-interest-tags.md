# 0049 · Feed 推荐流 Phase 1b：caption `#话题` 兴趣标签 + 用户兴趣画像 + 个性化召回/排序

> 状态：已实现并 `mvn ... compile` BUILD SUCCESS（tf-shared / tf-gateway / tf-feed-engine）。
> 范围：抖音式兴趣流的标签解析、兴趣画像累积、推荐流个性化召回与排序加权。

## 背景

Phase 1a 已完成「流量池赛马 + 完播率权重」（见 0046 及行为埋点）。Phase 1b 在之上引入
**内容标签 → 用户兴趣画像 → 个性化召回/排序**，使推荐流从「全局按入流时刻+完播率」进化为
「兼顾用户兴趣」的抖音式信息流。

## 设计要点

### 标签解析（共享工具）
- `tf-shared` 新增 `CaptionTagParser`：正则 `#([\p{L}\p{N}_·\-]+)`，小写归一、去重保序、单帖上限 10。
- 审核通过/入流时解析 caption → `tags`，随 `FeedItemView`（tf-shared 契约）物化进时间线成员串。

### 契约/视图
- `FeedItemView` 增 `tags`（tf-shared 已开 `-parameters`，无需 `@JsonProperty`）。
- `MediaItem`（gateway）增 `tags`（`@JsonProperty` 绑定；gateway 未开 `-parameters`）+ 便捷构造器从
  caption 自动派生 + `timelineKey()` 辅助；`FeedItemMapper` 双向映射；`MediaJdbcRepository` 两处构造点补 tags。

### 兴趣画像（Redis，新包 `com.turbofeed.feedengine.interest`）
- `TagIndexService`：`tf:tag:media:{tag}`(SET) 与 `tf:media:tags:{timelineKey}`(SET) 反向索引，
  append/remove 同步，TTL 7d，fail-open。
- `InterestService`：`tf:user:interest:{userId}`(Hash tag→weight)，`accumulateFromEvent` 按动作权重累积
  （LIKE +1.0 / COMMENT·SHARE +1.5 / WATCH +0.2 / DISLIKE −1.5 / IMPRESSION 不计），经
  `TagIndexService.tagsOf(timelineKey)` 反查被互动内容的标签；整份 TTL 30d（MVP 简化，不做逐事件衰减）。

### 个性化召回/排序
- `FeedTimelineStore.readPage(userId, page, size)` 排序分：`displayScore = recency + completionWeight·rate·3.6e6 + interestWeight·interestMatch·3.6e6`
  （interestMatch 为内容标签与用户画像正向权重累加，封顶 3.0；兴趣权重与完播率同量纲，避免压过时间序主轴）。
- 画像空 / 匿名（`userId=null`）→ 退化为纯「入流时刻 + 完播率」排序（冷启动口径不变）。
- 新增配置 `turbofeed.feed.interest-boost-weight`（默认 1.0）。

### 行为链路
- `FeedBehaviorEvent` 增 `userId`；`BehaviorEventPublisher` 接口 + `Http/RocketMqBehaviorEventPublisher` +
  `FeedBehaviorController` 从 `UserContextHolder.requireUserId()` 注入。
- `FeedController.recommended` 匿名安全取 userId（null→冷启动），经 `MediaQueryService` → `FeedEngineClient.recommended(page,size,userId)`
  透传至引擎 `/internal/feed/recommended?userId=`。
- `FeedTimelineController`（HTTP 接收端）与 `FeedBehaviorConsumer`（MQ 消费端）均累积兴趣；append 建标签索引、remove 摘索引。

## 偏离已批方案的一处（主动收敛）
- **`media_tag` 库表本期不建**：采用 Redis `TagIndexService` 作 MVP 标签存储，与「`user_interest`=Redis-only」
  决策一致，免去 128 表 DDL + ShardingSphere 重启的写-only 负担。落库作后续增强（建议按 `post_id` 而非
  `media_id` 分片，与引擎 `timelineKey` 口径一致）。见任务 #101（pending）。

## 验证
- 全模块 `mvn -pl tf-shared,tf-gateway,tf-feed-engine -am compile` BUILD SUCCESS。
- 运行期逻辑验证（执行真实 `CaptionTagParser` 类 + 个性化排序公式）：解析去重/小写/保序正确；
  兴趣匹配内容在相同 recency 下得分高于无匹配内容。
- 完整端到端（发 `#话题` 内容 → 审核通过入流 → 点赞 → 推荐流该标签内容前置）待 Redis 在线后执行。

## 改动文件
- 新增：`tf-shared/.../caption/CaptionTagParser.java`、`tf-feed-engine/.../interest/{TagIndexService,InterestService}.java`
- 修改：`FeedBehaviorEvent`、`FeedItemView`、`MediaItem`、`FeedItemMapper`、`MediaJdbcRepository`、
  `BehaviorEventPublisher` + 两个 publisher、`FeedBehaviorController`、`FeedController`、`MediaQueryService`、
  `FeedEngineClient`、`FeedTimelineController`、`FeedBehaviorConsumer`、`FeedTimelineStore`、`RecommendedFeedService`。
