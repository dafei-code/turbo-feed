# ADR-004：抽 tf-hotspot 缓存失效 SDK（P2-6）

- **状态**：已采纳（Accepted，2026-10-22）
- **所属改进项**：`improvement-plan.md` §3 / P2-6（热 Key L1 未实现）
- **决策人**：dafei
- **关联 ADR**：ADR-001（测试，本 ADR 配套 8 个单测）、ADR-003（tf-counter 热点治理思路一致）

## 1. 背景（Context）

`tf-gateway` 的 `AccountStateCache` 已实现一套成熟模式：**本地缓存（L1）+ Redis pub/sub 失效广播 + version stamp 防脏窗口 + 广播失败自动退化**。它把「账号级、低频变更」的信用/闸门数据从每次请求直读 DB，降到近乎零读，是 P0-2 安全语义（封禁/扣分后其它实例及时失效）的底座。

P2-6 指出：这套模式应抽成 `tf-hotspot` SDK 复用，供时间线热 Key 等场景。抽离前该模式与 gateway 强耦合——`AccountStateCache` 直接持有 `StringRedisTemplate`、独立实现广播与脏窗口逻辑，其它模块无法复用，且 `tf-hotspot`（P2-6 的目标模块）仍为空骨架。

## 2. 决策（Decision）

1. **框架无关的 SDK**：在 `tf-hotspot` 新增 `LocalCacheWithBroadcast<K,V>` + `CacheInvalidationPublisher` 契约（**零 Spring / 零 Redis 依赖**），封装 TTL + 上限熔断 + 周期清理 + 广播失效 + version stamp + 自动退化整套逻辑。接入方只提供 `CacheInvalidationPublisher` 实现与 `instanceId`/`keyParser`。
2. **AccountStateCache 重构为薄装配层**：持有 `level` / `write` 两个 SDK 实例（loader 不同），公共 API（`level`/`canWrite`/`invalidate`/`onInvalidateMessage`/`size`/`sweepExpired`）签名与行为**完全不变**；`INVALIDATE_CHANNEL` 常量与 `RedisCacheInvalidationConfig` 订阅**不变**。
3. **gateway 适配层**：新增 `RedisCacheInvalidationPublisher`（实现 `CacheInvalidationPublisher`，包 `StringRedisTemplate`，发送异常上抛由 SDK 吞掉并退化）。
4. **进程级 instanceId 共用**：`level`/`write` 两路 SDK 实例共用同一 `instanceId`，保证「自己发的广播自己跳过」，避免回环风暴。一次 `invalidate` 对两路各发一次广播（payload 相同、订阅方幂等），流量极低（仅封禁/扣分触发）。
5. **测试先行**：`LocalCacheWithBroadcastTest` 8 个纯单测（不依赖 Redis/Spring）覆盖 TTL 命中、version stamp 失效后必 reload、跨实例失效重载、自广播跳过、周期清理、上限整体清空、广播失败退化、关闭直读。

## 3. 后果（Consequences）

**正面**
- 热点治理模式可在 `tf-gateway` / `tf-feed-engine` / `tf-counter` 间复用（时间线热 Key 为下一步）；
- `AccountStateCache` 行为零变更，既有 gateway 测试（MediaReviewServiceTest 等）全绿，P0-2 安全语义无回退；
- SDK 零耦合、易单测，质量可独立保障。

**负面 / 成本**
- 一次 `invalidate` 发两次广播（幂等、极低频，可接受）；
- `tf-gateway` 新增对 `tf-hotspot` 的依赖；`tf-hotspot` 显式引入 `slf4j-api` 与 test 依赖。

## 4. 备选方案（Alternatives Considered）

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 直接换 Caffeine + 广播 | **否决** | 原设计刻意不用 Caffeine（仅需 TTL+上限、行为可预测），SDK 沿用 `ConcurrentHashMap` 保持语义一致 |
| 只抽接口不抽实现 | **否决** | P2-6 明确要抽「整套模式」（含广播/脏窗口/退化） |
| SDK + 重构 + 接入时间线热 Key 一步到位 | **递延** | 先证明模式再扩展，避免一次性改动热读路径、放大风险 |

## 5. 后续（Follow-ups）

- 时间线热 Key（如 `FeedTimelineStore` 的 `hotTimelineKeys` 读路径）接入 `LocalCacheWithBroadcast` L1，复用同一广播频道族。
- 真启动 e2e：起 gateway + Redis 集群，验证多实例间账号状态失效（SDK 单测已用双实例模拟覆盖，e2e 为加固项）。
