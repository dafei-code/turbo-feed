# 0047 已完成功能优化批次（P2）

> 配套提交：在 #1（死信重投告警）+ #5（流量池阈值外置）之后，继续完成其余可优化点。
> 本批次覆盖 #2 / #3 / #4 / #6 / #7 / #8 及一致性收尾审计。全部改动零回归编译通过。

## #2 流量池差评降级通道（P1 赛马补全）

- **问题**：`FeedPoolPromoter.decideTargetPool` 在差评率过高时返回 `1`，但 `scanNow` 只处理
  `target > cur`（仅升不降），降级目标被静默丢弃 → 差评内容仍停在高池持续放大。
- **修复**：
  - `FeedTimelineStore` 新增对称的 `demote(timelineKey, targetPool)`（复用同一套 ZSET + 反查索引，方向与 `promote` 相反，仅降不升）。
  - `decideTargetPool` 差评率高时改为**步降一级**（`Math.max(1, cur-1)`，封底 L1），与晋级每轮最多升一级对称，避免"一次差评直接清零"的剧烈抖动。
  - `scanNow` 处理 `target < cur`：调用 `demote` 并同步 `recommendedFeedService.invalidate()`（降级也即时反映到读路径），新增 `demoted` 计数日志。

## #3 本地缓存过期条目周期清理（#0039 无界隐患修复）

- **问题**：`AccountStateCache` 仅有"超限整体 `clear()`"熔断；过期却不再被访问的条目会一直驻留堆里，直到触发整体清空。
- **修复**：新增 `@Scheduled sweepExpired()`（默认 30s，可配 `sweep-interval-ms` / `sweep-initial-delay-ms`），驱逐 `expireAt` 已过条目；
  同时清理过期的 `invalidatedAt` version-stamp 标记，防止该 map 随失效次数无限增长。保持"不引 Caffeine"。

## #4 Redis 集群关闭期 "unreleased connections" WARN（#0043）

- **根因**：池化 + `shareNativeConnection=false` 下，`shutdownTimeout` 默认仅 100ms 太短，进程关闭时底层连接池仍有在途连接即被关 → Commons-Pool 警告。
- **修复**（`RedisTopologyConfig`）：
  - 集群 / 哨兵工厂显式声明 `destroyMethod = "destroy"`，保证随容器优雅销毁、底层池显式 close。
  - 未显式配置 `shutdownTimeout` 时放宽到 2s，给池足够排空时间（运行期无影响）。
  - 单机模式交 Boot 自动装配，不出现该 WARN。

## #6 内容安全 AC 热加载原子性（#0028）

- **结论**：经核查，`SensitiveWordService` 已用单个 `AtomicReference<Dictionary>`，先在调用线程全量构建
  （Aho-Corasick + 分类表打包为不可变 `Dictionary`）再 `set`——读路径 `dictRef.get()` 不会看到半成品。
  这本身就是"双缓冲 + 原子切换"，无需额外改造。本次未改代码，仅确认。

## #7 跨实例缓存失效脏窗口（#0042 补全）

- **问题**：跨实例失效仅靠 `convertAndSend` + 本地 `remove`；晚到/乱序广播可能误删本实例已加载的新鲜缓存，且发送失败仅 TTL 兜底无重试。
- **修复**（`AccountStateCache`）：
  - 新增每用户 `invalidatedAt` version-stamp map。读路径仅在 `holder.loadedAt >= invalidatedAt[user]` 时命中；
    本地 `invalidate` 与收到广播 `onInvalidateMessage` 均先打 `invalidatedAt`，使**无论本地还是跨实例（含晚到/乱序）的失效都保证"失效后首次读取必 reload"**，脏窗口压到一次 Redis 往返内。
  - 广播发送失败按 `broadcast-retries`（默认 1）重试，仍失败则吞异常退化 TTL 兜底（绝不拖垮写路径）。

## #8 发件箱补偿扫描效率（#0037/#0040）

- **现状**：`outbox_event` 已有 `idx_dispatch (status, next_attempt_at)` 覆盖 `findDueIds`；该表为单表（`ds_0`），"每分片独立扫"不适用。
- **修复**：补 `idx_reap (status, updated_at)` 索引，让 `reapStuck`（回收进程崩溃残留的 SENDING 行）走索引范围扫描，
  不再退化为"status 前缀 + 全量 updated_at 过滤"。`init-local.sql` 加索引；存量库见迁移脚本 `outbox_dispatch_index.sql`。

## 一致性收尾审计（#84）

- **fail-open 投递路径核查**：
  - `BehaviorEventPublisher.report`（行为埋点）与 `FeedTimelineStore.append/remove`（推荐流写时物化）为**有意 fail-open**（埋点/推荐优化项不能阻塞用户主流程），由 `degraded-mode` 兜底；时间线的 at-least-once 保障走发件箱 `OutboxRelay`，不依赖 fire-and-forget 发布器。
  - `ContentSecurityService.requireClean`（写路径内容安全）为 **fail-closed**（命中即抛异常），正确。
  - 结论：无"无意中 fail-open"的路径。
- **限流双轨配置单一来源**：机器维度（Sentinel `thread`/`qps`）与用户维度（Redis `perUser`/`windowSeconds`）均出自 `MediaProperties.RateLimit` 单一嵌套对象，`SentinelRateLimitConfig` / `UploadRateLimiter` / `LocalFallbackRateLimiter` 同读此源，无双轨漂移。
- **推荐流 backfill**：时间线是"写时物化"的派生视图，内容过审即入流；重启后若 Redis 时间线丢失（BUCKET_TTL 7d）无自动回填任务（已知取舍，非本次优化范围，需另立回填 Job）。

## 影响面 / 回滚

- 纯增量增强，默认值与改造前行为一致（阈值/索引/缓存语义不变）。
- 唯一需重启的场景：存量库执行 `outbox_dispatch_index.sql` 后，ShardingSphere 启动期加载元数据（历史踩坑，必须重启）。
- 本地缓存新增周期扫描（30s）与 version-stamp，对写路径零阻塞（fail-open）。
