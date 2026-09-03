# tf-counter 技术文档

> 分布式计数服务：Feed 场景的点赞 / 播放 / 评论计数。
> 形态：独立 Spring Boot 服务（端口 8081，`com.turbofeed.counter`）。
> **当前状态：设计骨架**（主类 + 配置就位，业务实现待填充），本文档同时是设计蓝图。

## 1. 设计目标

计数三难：写 QPS 高（点赞瞬时洪峰）、读扩散大（每条 Feed 展示都要带计数）、强一致不必需（用户对 ±1 不敏感）。

## 2. 架构方案（计数三级链路）

```
点赞事件 ──> MQ 削峰（RocketMQ）──> 分桶累加（Redis HINCRBY）
                                      │
                                      ├─ 定时批量落库（bucket-flush-interval-ms: 2000）──> MySQL（最终一致）
                                      └─ 读路径：L1 Caffeine（本机，TTL 3s）
                                              → L2 Redis（分桶聚合值）
                                              → L3 MySQL（兜底/冷数据）
```

| 层 | 职责 | 关键参数 |
|---|---|---|
| MQ | 削峰 + 失败重试，写路径异步化 | 复用 turbofeed.mq.* 总线 |
| Redis 分桶 | 热点计数打散到 N 桶（HINCRBY），读时 SUM 聚合 | `turbofeed.counter.bucket-count: 64` |
| 批量落库 | 定时聚合 flush，DB 写 QPS 恒定 | `batch-flush-interval-ms: 2000` |
| 三级读缓存 | L1 本机 → L2 Redis → L3 DB | L1 TTL 3s（容忍短时计数不准） |

热点 Key 协同：计数是典型热 Key 场景，L1 + 热点探测（tf-hotspot）联动，见 [tf-hotspot.md](tf-hotspot.md)。

## 3. 当前实现状态

- ✅ `CounterApplication` 主类、application.yml（8081 / actuator health,info / H2 兜底数据源）
- ✅ pom：web + actuator + repackage + Redisson（自动配置暂 exclude，Redis 就绪后删）
- ⬜ 分桶计数、MQ 消费、批量落库、三级缓存——待实现，落地时更新本文档

## 4. 依赖边界

- 依赖 `tf-shared`（Result / ErrorCode 契约）+ `tf-hotspot`（L1 / 热点探测 SDK）；
- 独占 Redisson（网关进程不加载，见架构总览通信矩阵）。
