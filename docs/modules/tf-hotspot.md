# tf-hotspot 技术文档

> 热点探测与治理 SDK。**进程内库**（不独立部署）：嵌入 tf-counter / tf-feed-engine 进程使用。

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

设计蓝图（pom 依赖 Caffeine 就位，代码待填充）。落地时更新本文档并补充与 tf-counter / tf-feed-engine 的集成方式。
