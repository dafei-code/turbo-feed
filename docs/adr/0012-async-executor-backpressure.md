# ADR-0012：异步执行器背压分层（可丢活 vs 持久化活）

- **状态**：已采纳（Accepted, 2026-11-01）
- **所属改进项**：架构评审 #1（异步执行器拒绝策略反模式）
- **决策人**：dafei
- **关联 ADR**：[0007 可观测性](0007-observability.md)（新增 `gateway.async.*` 计数器经 `/actuator/prometheus` 暴露）、[0009 通知中心](0009-notification-center.md)（通知生成改走 bestEffort 执行器）

## 1. 背景（Context）

网关 `AsyncConfig` 原把三个执行器（`reviewExecutor` / `uploadFinalizeExecutor` / `interactionExecutor`）**全部**设为 `ThreadPoolExecutor.CallerRunsPolicy`。其中 `interactionExecutor`（队列 1024）被两类语义截然不同的异步活共用：

1. **持久化双写**（绝不能丢）：点赞 / 收藏 / 关注 / 评论落库——Redis 为权威，DB 仅为灾备重建兜底；
2. **可丢旁路**（fail-open）：`NotificationGenerator` 通知生成、`InteractionEventService` 交互行为样本落库——任一丢失不影响主链路正确性。

**问题**：`CallerRunsPolicy` 在池与队列打满时，把异步任务**退回调用线程同步执行**。对第 2 类「可丢活」而言，这等于在高压下违背了「绝不阻塞主链路」的承诺——推荐流 / 互动接口会因通知生成、样本落库的慢 DB 而被拖慢，甚至雪崩。换言之，原本为「防丢」设计的策略，反而成了「拖垮主链路」的隐患。

**约束（决定不能一刀切）**：第 1 类持久化活必须「宁可慢、绝不丢」，否则 Redis 重启后无法据 DB 重建；第 2 类可丢活必须「宁可丢、绝不阻塞」。两者不能共用同一拒绝策略。

## 2. 决策（Decision）

按「是否可丢」对异步活做**背压分层**：

1. **新增 `bestEffortExecutor`**（可丢活专用）：`core=2 / max=8 / queue=256`，拒绝策略 `DiscardWithMetricPolicy`——池与队列饱和时**直接丢弃**任务（fail-open），计数 `gateway.async.best_effort.dropped` + 采样 WARN，绝不阻塞调用方。
   - 绑定：`NotificationGenerator#generate`（通知生成）、`InteractionEventService#recordImpression` / `record`（交互样本）。
2. **`interactionExecutor` 保留 `CallerRunsPolicy`**（持久化活专用）：点赞 / 收藏 / 关注 / 评论落库继续走它，保证「宁可慢、绝不丢」。
   - 用 `CountingCallerRunsPolicy` 包装：保留退回调用线程语义，额外计数 `gateway.async.interaction.saturated`，把「已开始 caller-runs」暴露为容量红线告警源（一旦开始 caller-runs，说明上传 / 互动主链路已被异步尾巴拖慢）。
3. **JDBC 语句超时兜底**（Plan B 配套）：`shardingsphere-config.yaml` 的 ds_0 / ds_1 `jdbcUrl` 追加 `&socketTimeout=30000`，使单条 SQL 超过 30s 由驱动中止，避免 caller-runs 期间连接线程被慢查询无限期占用。
4. **指标可观测**：两个计数器经 Actuator 的 `MeterRegistry` 注册，由 `/actuator/prometheus` 暴露，接入既有 Prometheus（ADR-0007）。

## 3. 后果（Consequences）

**正面**

- 第 2 类可丢活在高压下真正「不阻塞主链路」，把 fail-open 承诺兑现到底；
- 第 1 类持久化活语义不变（仍 CallerRuns 防丢），无回归；
- 背压信号可量化：丢弃数 / 饱和数均有指标，容量规划与告警有据可依；
- `socketTimeout` 给慢 DB 兜底，避免单条烂 SQL 卡死线程。

**负面 / 成本（必须正视）**

- **可丢活在极端过载时会真的丢**：通知生成在 best-effort 饱和时被丢弃——有 `gateway.async.best_effort.dropped` 指标可观测，且通知本就是 fail-open（少一条角标不影响正确性）；交互样本丢弃仅使模型学偏一点点，可接受。
- **`socketTimeout=30000` 仅覆盖提交版默认 URL**：生产由环境变量 `TURBOFEED_DB_0_URL` / `TURBOFEED_DB_1_URL` 注入的真实 URL 必须也带 `socketTimeout`（运维侧同步，本 ADR 不改生产连接串）。
- **`interaction.saturated` 计数 > 0 即告警**：意味着容量已触顶、主链路已被拖慢，需扩容或削峰，是运维红线而非普通指标。

## 4. 备选方案

| 方案 | 否决理由 |
|---|---|
| 全部执行器统一 CallerRuns（现状） | 可丢活被退回调用线程同步执行，高压下拖垮推荐 / 互动主链路，与「不阻塞主链路」承诺矛盾 |
| 全部执行器统一 DiscardPolicy | 持久化双写（点赞 / 收藏 / 关注 / 评论）会被静默丢弃，Redis 重启后无法据 DB 重建，数据丢失不可接受 |
| 可丢活也用 CallerRuns + 业务侧手动降级 | 降级阈值难定、且仍会在降级前拖慢主链路；不如执行器层直接 discard 干净 |
| 加大 interactionExecutor 队列避免拒绝 | 队列无限加大只把背压转成内存与延迟，不治本；且可丢活本就不该占持久化池资源 |

## 5. 落地记录

- `tf-gateway/.../config/AsyncConfig.java`：新增 `bestEffortExecutor` bean + `DiscardWithMetricPolicy` / `CountingCallerRunsPolicy` 两个指标化拒绝策略；`interactionExecutor` 改绑 `CountingCallerRunsPolicy`。
- `tf-gateway/.../service/notify/NotificationGenerator.java`：构造器 `@Qualifier("interactionExecutor")` → `@Qualifier("bestEffortExecutor")`。
- `tf-gateway/.../service/feed/InteractionEventService.java`：两处 `@Async("interactionExecutor")` → `@Async("bestEffortExecutor")`。
- `tf-gateway/src/main/resources/shardingsphere-config.yaml`：ds_0 / ds_1 `jdbcUrl` 追加 `&socketTimeout=30000`。
- 未动：点赞 / 收藏 / 关注 / 评论等持久化双写仍走 `interactionExecutor`（CallerRuns，绝不丢）。
