# tf-counter 技术文档

> 分布式计数服务：Feed 场景的点赞 / 播放 / 评论计数。
> 形态：独立 Spring Boot 服务（端口 8081，`com.turbofeed.counter`）。
> **当前状态：已落地**（17 个源文件 / ~990 行：分桶累加、MQ 消费削峰、定时批量落库、三级读缓存均已实现并可用）。
> 本文 §2 仍保留完整设计蓝图（含参数口径），§3 为**现状清单**——两者不一致时以 §3 为准。
> 待补：与 `tf-hotspot` 的 L1 联动（当前 L1 由本模块直接用 Caffeine 自建，未接入 hotspot SDK）。

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

**已完成（代码在 `tf-counter/src/main/java/com/turbofeed/counter/`）**：

- ✅ 主类与配置：`CounterApplication`、`CounterProperties`（分桶数 / flush 间隔 / L1 TTL）、`ShedLockConfig`（多实例 flush 互斥）
- ✅ 写路径：`CounterIncrService`（分桶 HINCRBY）、`CounterBuffer` / `RedisCounterBuffer` / `CounterBufferService`（进程内聚合缓冲）
- ✅ MQ 削峰：`CounterMqConsumer`（消费计数事件）、`CounterIncrController`（HTTP 兜底入口）
- ✅ 批量落库：`CounterFlushService` + `CounterFlushScheduler`（单机定时）/ `ClusteredCounterFlushScheduler`（ShedLock 集群单点调度）→ `PostCounterRepository`
- ✅ 读路径：`CounterReadService` —— L1 Caffeine（5000 条 / TTL 60s）→ L2 Redis（`RedisCounterStore`，**opt-in**，默认关）→ L3 DB（`post_counter` 权威源），逐层回写
- ✅ 持久化：`PostCounterRepository`（H2 兜底 + MySQL），已有 `PostCounterRepositoryH2Test`

**待补**：

- ⬜ 与 `tf-hotspot` 联动：L1 目前是本模块自持的 Caffeine 实例，未接入 `LocalCacheWithBroadcast`（跨实例失效广播）。当前多实例下 L1 最长有 60s 不一致窗口。
- ⬜ `tf-benchmark` 压测验证（分桶数 / flush 间隔的最优值目前是经验值，缺客观数据）

## 4. 依赖边界

- 依赖 `tf-shared`（Result / ErrorCode 契约）+ `tf-hotspot`（L1 / 热点探测 SDK）；
- 独占 Redisson（网关进程不加载，见架构总览通信矩阵）。
