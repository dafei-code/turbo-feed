# tf-hotspot 技术文档

> 热点探测与治理 SDK。**进程内库**（不独立部署）：按设计嵌入 tf-counter / tf-feed-engine 进程使用。
> **当前状态：部分落地** —— 「L1 本地缓存 + 跨实例失效广播」**已实现并在用**（2 个源文件，被 tf-gateway 的 `AccountStateCache` 与 tf-feed-engine 的 `HotTimelineKeyCache` 调用，另有 `LocalCacheWithBroadcastTest`）；
> 「热 Key 探测」（滑动窗口计数判热，§2 第一项）**尚未实现**。本文 §2 中**只有热探测与热/冷桶迁移是蓝图**，L1 + 广播部分已是现状描述。

## 1. 为什么是库不是服务

L1 缓存天然属于使用它的进程（本机内存）；热点探测需要就近统计访问计数。独立成服务反而增加一跳网络，违背"热 Key 本地化"初衷。

## 2. 设计方案

```
业务访问 ──> 滑动窗口计数（window-seconds: 10）
              └─ 窗口内访问 > hot-threshold: 1000 → 判定热 Key
                    ├─ 本地提升：进 L1 Caffeine（l1-ttl-seconds: 3，短 TTL 防击穿后不一致）
                    └─ 广播通知其他实例：热 Key 集合同步（避免所有实例各自重复探测）
```

| 能力 | 说明 | 参数 |
|---|---|---|
| 热探测 | 滑动窗口访问计数超阈值 | `turbofeed.hotspot.window-seconds: 10` / `hot-threshold: 1000` |
| L1 本地缓存 | Caffeine，热 Key 进程内缓存 | `l1-ttl-seconds: 3` |
| 热/冷桶迁移 | 配合计数分桶，热 Key 走独立桶隔离写热点 | 与 tf-counter.bucket-count 联动 |
| 广播 | 实例间热 Key 同步 | 经事件总线（MQ）广播 |

## 3. 当前实现状态

**已完成**：

- ✅ `LocalCacheWithBroadcast<K,V>`：L1 本地缓存 + Redis pub/sub 失效广播；version stamp 防脏窗口（失效后首次读取必 reload）；超限整体清空（非 LRU，保护性熔断）；`sweepExpired()` 周期清理；零 Spring / 零 Redis 依赖（仅依赖 `CacheInvalidationPublisher` 契约）
- ✅ `CacheInvalidationPublisher`：失效广播发布契约（gateway 侧实现为 `RedisCacheInvalidationPublisher`）
- ✅ `LocalCacheWithBroadcastTest`
- ✅ 接入方：tf-gateway `AccountStateCache`（账号状态缓存）、tf-feed-engine `HotTimelineKeyCache`（热时间线 Key 缓存）

**待补**：

- ⬜ 热 Key 探测：滑动窗口访问计数（`window-seconds` / `hot-threshold` 已配置但**无代码消费**），判热后自动进 L1 并广播热 Key 集合
- ⬜ 热/冷桶迁移：与 `tf-counter.bucket-count` 联动，热 Key 走独立桶隔离写热点
- ⬜ tf-counter 的 L1 接入：目前 tf-counter 自持 Caffeine，未用本 SDK（见 [tf-counter.md](tf-counter.md) §3）
