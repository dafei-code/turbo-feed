# ADR-0010：分享 / 转发（Share & Forward）设计

- **状态**：提议（Proposed, 2026-10-31）
- **所属改进项**：产品级能力缺口 #342（P0 产品级）
- **决策人**：dafei
- **关联 ADR**：[0003 tf-counter](0003-tf-counter-service.md)（分享计数复用计数维度）、[0008 读模型分层](0008-timeline-layering.md)

## 1. 背景（Context）

当前缺分享 / 转发。抖音式产品需要：分享到站外（微信 / QQ / 复制链接 / 二维码）+ 站内转发（引用原帖二次分发）。价值是裂变拉新、内容破圈。现状：`media` / 帖结构不支持转发引用，也无分享动作日志与计数。

**约束**

- 分享是「动作 + 计数」，不应复制内容主体（否则原内容删除后副本成孤儿，且存储膨胀）；
- 但分享卡片要展示标题 / 首图 / 作者 → 分享时刻定格**快照**，原内容删除后卡片仍可显示快照、点击回源提示已删除；
- 防刷：分享可被刷量（刷外链、刷计数），必须有频控。

## 2. 决策（Decision）

1. **分享 = 引用式动作 + 快照**，不复制内容主体：
   - `share_record`（分片键选 `target_id` 用 `HASH_MOD`：写多读少，按 target 聚合统计「某内容被分享 N 次」是主读场景；用户维度「我的分享」读少，可接受回源）：列 `id, user_id, target_type, target_id, channel, snapshot_json, short_code, created_at`。
   - `snapshot_json` 定格 `{title, coverUrl, authorName, authorId}`（分享时刻抓取，之后不变）。
2. **站内转发 = 新帖引用**：在 `media` / 帖结构加 `ref_post_id`（nullable）。转发发布时建一条新帖，`ref_post_id` 指向原帖；推荐 / 详情展示「转发自 @author」。复用现有帖生命周期（审核 / 时间线投递 / 推荐），**不新建大表**。原帖删除不影响转发帖（转发帖自带文案 / 快照）。
3. **渠道枚举**：`IN_APP / WECHAT / QQ / COPY_LINK / QR`。短链：`tf:share:short:{code}` → `{targetType, targetId, trace}`（Redis，带归因 `trace` 参数回源）。
4. **计数**：`share_count` 作为 tf-counter 第 N 维（计数服务已落地，加维度即可），实时 + 异步落库；详情页 / 卡片展示。
5. **防刷**：同 `(user_id, target_id)` 频控（Redis 滑动窗口，如 1 次 / 秒）+ 单内容全局分享频控；超阈值丢弃并返回软成功（fail-open，不阻断用户）。
6. **回源归因**：短链跳转带 `?from=share&trace=xxx`，原帖详情记录分享来源（进 behavior_log 供画像 / 推荐）。

## 3. 后果（Consequences）

**正面**

- 裂变能力补齐；引用式避免孤儿副本与存储膨胀；
- 计数复用 tf-counter，零新基础设施。

**负面 / 成本**

- **快照定格**：内容后续修改仍显示分享时刻的旧快照（产品常态，可接受，但需在卡片标注「分享时」或接受）；
- **短链治理**：`tf:share:short:{code}` 需容量规划与 TTL（长期有效 vs 存储成本），二维码生成需服务端或前端库；
- **防刷调参**：阈值过严误伤正常用户、过松被刷，需按数据调参；
- **转发帖推荐去重**：G6 / 推荐去重逻辑需识别 `ref_post_id` 避免同内容重复推。

## 4. 备选方案

| 方案 | 否决理由 |
|---|---|
| 分享即复制帖子 | 孤儿副本、存储膨胀、删除语义混乱（原帖删副本怎么办） |
| 转发也建独立表 | 与现有帖生命周期（审核 / 时间线 / 推荐）重复，双写易不一致，放弃 |
| 不防刷 | 分享计数被刷量污染、外链被滥发，否决 |

## 5. 落地记录（待做，纯设计）

- `media` 加 `ref_post_id` 列（DDL + 上传 / 发布链路透传）。
- `share_record` DDL + ShardingSphere 登记；`ShareService` + `ShareController`（`/api/v1/share/create | my | stats`）+ 短链生成。
- tf-counter 加 `share` 维度；防刷 Redis 滑动窗口。
- `feed.html` / 详情页加分享按钮与转发入口；前端生成分享卡片（快照渲染）。
