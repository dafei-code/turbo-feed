# tf-feed-engine 技术文档

> Feed 推拉结合核心引擎。
> 形态：独立 Spring Boot 服务（端口 8082，`com.turbofeed.feedengine`）。
> **当前状态：设计骨架**（主类 + 配置就位，业务实现待填充），本文档同时是设计蓝图。

## 1. 设计目标

推拉结合：普通用户发布走**推**（写扩散到粉丝收件箱），大 V 发布走**拉**（读时从 outbox 聚合），活跃度分层避免向不活跃用户无效扇出。

## 2. 架构方案

```
发布事件 ──> 扇出 Worker（活跃度分层过滤）
              ├─ 普通用户：推 → 收件箱 Redis List（LTRIM 定长 inbox-max-size: 2000）
              └─ 大 V（粉丝 > push-threshold: 1000）：只写 outbox（ZSET，保留 outbox-retain-days: 7 天）

读 Feed ──> 多路归并：收件箱（推的部分） + 关注列表中大 V 的 outbox（拉的部分）
            └─ 按时间归并，取首屏 inbox-read-size: 500 条
```

| 机制 | 落点 | 关键参数 |
|---|---|---|
| 收件箱 | Redis List + LTRIM 定长裁剪 | `turbofeed.fanout.inbox-max-size: 2000` |
| 大 V 判定 | 粉丝数阈值，超阈值转拉模式 | `push-threshold: 1000` |
| outbox | 大 V 发布时间线（ZSET），按 score 拉取 | `outbox-retain-days: 7` |
| 活跃度分层 | N 日未登录跳过扇出，省写扩散 | `inactive-days: 7` |
| 多路归并 | 收件箱 + outbox 归并排序，首屏条数 | `inbox-read-size: 500` |

## 3. 当前实现状态

- ✅ `FeedEngineApplication` 主类、application.yml（8082 / actuator / Redisson 自动配置暂 exclude）
- ✅ pom：web + actuator + repackage + Redisson
- ⬜ 扇出 Worker / 收件箱 / outbox / 分层 / 归并——待实现，落地时更新本文档

## 4. 依赖边界

- 依赖 `tf-shared` + `tf-hotspot`（大 V 收件箱是热 Key 场景：千万粉丝读扩散）；
- 独占 Redisson；UGC 图片（网关侧审核通过的内容）作为 Feed 项媒体来源，经事件总线或元数据查询对接。
