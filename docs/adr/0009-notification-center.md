# ADR-0009：通知中心（Notification Center）有界上下文设计

- **状态**：提议（Proposed, 2026-10-31）
- **所属改进项**：产品级能力缺口 #342（P0 产品级）
- **决策人**：dafei
- **关联 ADR**：[0003 tf-counter](0003-tf-counter-service.md)（通知未读计数复用计数维度）、[0008 读模型分层](0008-timeline-layering.md)（收件箱与 timeline 读模型同构）

## 1. 背景（Context）

当前 turbo-feed **没有通知中心**。用户产生的互动（点赞 / 评论 / 回复 / @ / 关注 / 收藏）与系统事件（审核通过 / 驳回 / 系统公告）发生后，接收方没有任何统一入口感知。直接后果：

1. 创作者不知道谁赞了 / 评了自己的作品 → 互动留存上不去；
2. 审核结果无法触达提交者 → 「我的内容」状态只能靠用户自己刷；
3. 关注流（G6 已落地）有了新粉丝却无通知 → 关注关系沦为单向，社交闭环断一节。

抖音式产品通知至少要有：赞、评论 / 回复、@、新粉丝、系统公告、作品状态变更。这是 P0 级产品能力，当前完全空缺。

**约束（决定不能靠自觉）**

- 写路径事件来源分散：点赞走 `MediaLikeService` / tf-counter、评论走 comment 服务、关注走 `FollowService`、审核走 `MediaReviewService`。通知生成必须解耦，不能让每个业务方都直接写通知表；
- 读路径是典型「收件箱」模型：某用户的时间倒序列表 + 未读角标，读多写少、要求低延迟；
- 必须 **fail-open**：通知生成失败绝不允许阻塞主互动链路（因为通知写挂了导致点赞失败，是不可接受的）。

## 2. 决策（Decision）

1. **写侧事件驱动、单点生成**：各业务方只发领域事件（新增 `NotificationEvent`，或复用现有互动事件总线），由 `NotificationGenerator` 消费并写 `user_notification` + 更新读模型。业务方不直接依赖通知表。
2. **存储分层**（沿用 ADR-0008 的「写模型 + 读模型」分层）：
   - **写模型（OLTP）**：`user_notification` 分片表（ShardingSphere 登记，分片键 `user_id` 用 `HASH_MOD`，与 `user` 表同 4 分片或随扩容 128），autoTables 连续编号）。列：`id, user_id, type, actor_id, target_type, target_id, summary, ref_payload, read, created_at, revision`。
   - **读模型（Redis）**：收件箱 ZSET `tf:notify:{userId}`（score = 毫秒时间戳，member = notifyId）；未读计数 `tf:notify:unread:{userId}`（INCR / DECR）。列表读 ZSET 再 mget 详情；角标读计数。
3. **实时触达**：网关层 `WebSocket /notify/ws`（或 SSE）推送「未读 +1」轻量事件；**fail-open 降级**为前端 15s 轮询 `GET /api/v1/notify/inbox?cursor=`（无 WS 也工作）。推送失败仅记日志，不影响写。
4. **分类枚举**：`LIKE / COMMENT / REPLY / MENTION / FOLLOW / FAVORITE / SYSTEM / REVIEW_RESULT`。
5. **聚合去重**：同 `(actor_type, type, target_id)` 在 60s 窗口内合并为「X 等 N 人做了 Y」（Redis 滑动窗口计数，避免轰炸）；系统公告走广播任务写全量用户收件箱（分批异步 + 限流）。
6. **已读**：`POST /api/v1/notify/read`（按 id 列表或按 type 全清）+ 清未读计数。

## 3. 后果（Consequences）

**正面**

- 互动闭环补齐，创作者感知自己内容的互动；
- 读模型与 timeline 同构，`tf:notify:` 风格统一、易维护；
- fail-open 不影响主互动链路。

**负面 / 成本（必须正视）**

- **双写一致性**：通知生成成功但 Redis 写失败时，列表缺一条。兜底：ZSET 缺失则回源 MySQL 按 `userId` 查最新 N 条（收件箱读路径本就要回源详情，顺带兜底）。
- **WebSocket 多实例**：网关多实例需共享连接（Nacos 已落地），用 Redis pub/sub 跨实例广播或实例本地推送 + 广播补发；增加一套连接管理。
- **系统公告写放大**：全量用户收件箱写入对大用户量是写放大，必须分批异步 + 限流（单批百级、节流），且新建用户要补历史公告（或公告只发给「创建时间晚于公告」的用户）。
- **通知生成是异步消费**：用户点完赞到收到通知有毫秒~秒级延迟（产品可接受，但 review 结果通知需保证最终到达，消费侧至少一次 + 幂等）。

## 4. 备选方案

| 方案 | 否决理由 |
|---|---|
| 各业务方直接 `INSERT` 通知表 | 耦合、易漏（新增互动类型要改 N 处）、无法统一聚合 / 限流 / 已读语义 |
| 纯 MySQL 读（不建 Redis 读模型） | 未读角标是高频读，直查分片表在大用户量下扛不住；与现有 timeline / 计数器读模型风格不一致 |
| 只轮询不接 WS | 实时性差；但保留为降级路径，WS 是可选项而非必需，故不否决、只是默认值 |

## 5. 落地记录（待做，纯设计）

- 新增 `user_notification` DDL + ShardingSphere 登记（`HASH_MOD`，键 `user_id`）。
- `tf-gateway` 新增通知模块：`NotificationGenerator`（消费事件）+ `NotificationRepository` + `NotificationController`（`/api/v1/notify/inbox | unread | read`）+ WebSocket / SSE 端点。
- 读模型 Redis 双写 + 回源兜底；未读计数接 tf-counter 维度或独立 INCR。
- 各业务方埋 `NotificationEvent`（`MediaLikeService` / CommentService / `FollowService` / `MediaReviewService`）。
- `admin.html` / `user.html` 加通知中心 tab + 角标。
