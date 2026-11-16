# PR-D4 发件箱水位巡检指标（实施口径）

> 对应 ADR：`docs/adr/0016-pr-bcd-implementation-plan.md` §6。
> 状态：代码已实施。本文记录**实际落地状态**与 ADR 的差异，避免后续按 ADR 重复造轮子。

---

## 一、ADR §6 三项指标的真实状态

| ADR 要求 | 实际状态 | 本轮动作 |
| --- | --- | --- |
| `outbox_oldest_pending_age_seconds`（Gauge，只查 PENDING） | **改造前已存在**：`tf_outbox_oldest_age_seconds{status}`，按 PENDING / SENDING / FAILED / DEAD 四种非终态分别注册，且 `OutboxEventRepository.backlog()` 已用 `MIN(created_at)` 算最老年龄 | **跳过**：功能已被覆盖且比 ADR 要求更全，重复造同名指标只会制造两套读数 |
| `outbox_dead_total{type}` | **PR-C3 已落地**：`tf_outbox_dead_total{type}`，在 `OutboxService.tryDeliver` 判死处计数，与 `DeadLetterAlert` 同批触发 | **跳过**：不重复计数 |
| `outbox_relay_round_duration_seconds`（Timer） | **真缺口**：`OutboxRelay` 改造前只有一行日志，且空轮不打日志 | **本轮补齐**：`tf_outbox_relay_round_duration_seconds` |

> 命名口径：ADR 表格里的名字不带 `tf_` 前缀，项目发件箱既有指标一律带该前缀
> （`tf_outbox_backlog` / `tf_outbox_oldest_age_seconds` / `tf_outbox_dead_total`），
> 本轮沿用既有命名空间，便于同一套 Grafana 面板与告警规则复用。

---

## 二、本轮补齐的两个信号

### 1. `tf_outbox_relay_round_duration_seconds`（Timer）

| 项 | 说明 |
| --- | --- |
| 计时范围 | `OutboxRelay.relay()` 全程（含 `reapStuck` 回收、到期查询、逐条 `tryDeliver`） |
| 写入位置 | `finally` 块，异常轮也记录 |
| 语义 A | `totalTime / count` 持续大于 `poll-interval-ms` → 批量或下游消费能力不足 |
| 语义 B | Timer 自带 `count`，`rate(count)` 掉到 0 → **Relay 假死**（调度线程不再产出） |
| 依赖注入 | `MeterRegistry` 用可选字段注入；缺失时整体跳过计时，既有四参构造器与单测零回归 |

为什么不能靠日志：空轮不打日志，「正常空转」与「线程活着但不再产出」在日志上完全一样。
Timer 即使一轮耗时接近 0，`count` 仍稳定增长，这才是可判定的心跳。

### 2. `tf_outbox_backlog_refresh_failed_total`（Counter）

`OutboxBacklogMetrics` 类注释里早就写明一个未补的局限：查询失败时水位**保留上一轮值**并只打日志，
在 Grafana 上「指标停在旧值」与「积压真的没变化」长得一模一样；首次刷新就失败时，Gauge 的 0
与真实空积压也不可区分。本轮在 `refreshInternal()` 的失败分支补计数器：

| 场景 | 读数 |
| --- | --- |
| 查询成功 | Gauge 刷新，计数器不变 |
| 查询失败 | Gauge 保留上一轮值，计数器 +1 |
| 首次即失败 | Gauge 为 0、计数器为 1 → 可判定「这个 0 不可信」 |
| 恢复成功 | Gauge 归零，计数器停止增长 |

---

## 三、建议告警规则

| 规则 | 表达式要点 | 含义 |
| --- | --- | --- |
| Relay 假死 | `rate(tf_outbox_relay_round_duration_seconds_count[5m]) == 0` | 补偿通道不再产出，快路径若同时失败即静默丢事件 |
| Relay 变慢 | `rate(tf_outbox_relay_round_duration_seconds_sum[5m]) / rate(..._count[5m])` 持续大于 `poll-interval-ms` | 批量/下游能力不足 |
| 水位刷新不可信 | `increase(tf_outbox_backlog_refresh_failed_total[5m]) > 0` | 当前显示的积压值可能是旧值 |
| 积压年龄（已有） | `tf_outbox_oldest_age_seconds{status="PENDING"} > 60` / `> 300` | 快路径正常情况下 PENDING 年龄应约等于 0 |
| 判死（PR-C3 已有） | `increase(tf_outbox_dead_total[5m]) > 0` | 任何判死都应进入告警通道 |

---

## 四、验证

- `OutboxRelayMetricsTest`：空轮计数、多轮累加、异常轮仍计时、MeterRegistry 缺失零回归。
- `OutboxBacklogRefreshFailureTest`：失败保留旧值 + 计数、首轮失败可区分、恢复后不再计数。
- 既有 `OutboxRelayTest`（4）/ `OutboxBacklogMetricsTest`（5）/ `OutboxHandlerRegistryTest` 全绿，零回归。
- 整仓全量单测与 `mvn -Plocal package -DskipTests` 见 `#405` 统一验证记录。
