# ADR-0007：可观测性口径（指标命名 / 告警口径 / traceId 分层）

- **状态**：已采纳（Accepted，2026-10-27）
- **所属改进项**：`improvement-plan.md` §3 / M4
- **决策人**：dafei
- **关联 ADR**：[0005](0005-contract-versioning.md)（MQ 侧 traceId 缺失需升 schemaVersion，与契约演进耦合）

## 1. 背景（Context）

M4 之前，本项目的观测手段**只有日志**。具体缺口（M2-4.4 的对账文档登记过）：

| 缺口 | 当时的处境 |
|---|---|
| 发件箱积压 | `OutboxRelay` 每轮打印 `due/成功/失败`，回答不了「现在积压多少、最老一条卡多久」 |
| 死信 | 只打一行 `log.error`，没有累计值，无法驱动告警 |
| 计数 flush | 只有日志里的批次大小，`dedup` 命中数完全没有出口 |
| MQ 消费 | 只有失败日志，没有失败率 |
| 请求链路 | **全仓零 MDC**，一次报障要在两份日志里按时间戳手工对齐 |

补指标本身不难，难的是**口径**：同样一个「失败次数」，用计数告警还是用失败率告警，
在真实故障里是「半夜被噪声叫醒」和「漏掉全线故障」的区别。

## 2. 决策（Decision）

### 2.1 告警用失败率，不用裸计数

所有「失败 / 丢弃 / 重放」类事件，必须同时暴露**分母**和**分子**：

- `tf_feed_timeline_consume_total{action}` + `tf_feed_timeline_consume_failed_total{action}`
- 分母不存在时用 `clamp_min(..., 0.001)` 兜住除零

理由：「每秒 3 次失败」在总量 3000/s 时是噪声、在总量 5/s 时是全线故障。裸计数无法区分，
告警规则只能拍到脑袋定阈值。

**推论**：失败**不计入分母**。否则失败率被自己的失败稀释——服务越坏，这个数越接近 0。

### 2.2 成功路径不创建失败时序

计数为 0 的时序**不注册**。因为 Prometheus 里「时序不存在」与「时序恒为 0」语义不同，
`absent()` / `rate()` 的行为都会变。凭空造出「失败率 0」的时序，会让
`absent(failed_total) == 1` 这类「从来没数据 = 链路根本没跑」的规则永远失效。

**反例（本决策的例外）**：Gauge 类积压指标走相反策略——**启动即注册为 0**。
原因是积压的语义是「现在是几」，归零必须看得见 0 而不是时序消失。
两者不矛盾：Counter 描述「发生过多少次」（没发生就不该有时序），Gauge 描述「此刻是多少」
（为 0 也是一个有效观测值）。

### 2.3 统计类指标失败时保留旧值（fail-quiet）

积压统计查 DB 失败时**不置 0**，保留上一轮的值并打 warn 日志。
指标停在旧值比掉到 0 更接近事实——掉 0 会被读成「积压已消化」，
正好把最需要人看见的情况伪装成好事。

### 2.4 指标命名

`tf_<域>_<对象>_<单位/语义>`，Prometheus 侧 snake_case；Counter 显式以 `_total` 结尾
（Micrometer 检测到已带 `_total` 不会重复追加）；延迟类一律 `_seconds`。
标签基数必须**有界**：只按枚举型字段（状态 / 事件类型 / action）分标签，
绝不按 `postId` / `userId` 这类无界值。

### 2.5 traceId 分层：纯逻辑共享，servlet 管道各自一份

- **tf-shared `TraceIds`**：生成、白名单校验、header 名、MDC 键。**纯 JDK**。
- **网关 / 引擎各一个 `TraceIdFilter`**：MDC put/remove + 响应头回写。**重复两份**。

为什么宁可重复也不下沉：tf-shared 的 `ArchitectureTest` 禁止任何非 JDK 依赖
（连 `org.slf4j` 都在禁令内），因为它是被每个服务内嵌的共享库，框架耦合会顺着依赖图扩散。
为一段四十行的管道代码破例，代价是给所有服务（含不该有 servlet 的 tf-redis / tf-hotspot）
引入 servlet 依赖。

两份副本的**唯一契约锚点**是 `TraceIdsTest` 里锁死的 header 名与 MDC 键——改任一处它会红。

### 2.6 入站 traceId 必须先过白名单

长度 ≤64、字符 `[A-Za-z0-9._-]`，不合规**重新生成而不是报错**。

不是洁癖：带 `\n` 的 header 能把一整条伪造日志行写进文件（伪造审计记录、污染 grep），
超长值会撑爆日志与采集链路。宁可丢上游的关联性，也不让不可信内容进日志。

### 2.7 出站透传必须覆盖**所有** builder

`directRestClientBuilder` 与 `loadBalancedRestClientBuilder` **两个都加**拦截器。
只加直连那份的话，一旦开启 Nacos 走 LB 分支，traceId 会在网关→引擎这一跳静默断开——
两边都能正常跑，只是日志再也串不起来，而且没人会主动去查。

## 3. 后果（Consequences）

**正面**
- 排查从「按时间戳对齐两份日志」变成「一条 grep 拉出整条链」；
- 告警规则有可推导的阈值（失败率），而不是拍的绝对数；
- 指标的「缺失 vs 为 0」语义被显式区分，`absent()` 类规则可用。

**负面 / 成本**
- **没有引入 OpenTelemetry / Sleuth**，因此没有 span、没有分布式调用树、没有自动埋点。
  自己实现的 traceId 只解决「日志能串起来」，解决不了「每个环节耗时多少」。
  这是本轮刻意的范围限制——引入 OTel 会带进 agent、exporter、采样策略一整套，
  与「先有基本信号」的当前阶段不匹配。
- **MQ 消费侧拿不到生产者 traceId**：契约里没有该字段，加字段等于升 `schemaVersion`。
  消费链路的日志用消费时刻新生成的 id，与生产请求的链是断的。
- **`tf-counter` 没有入站 traceId**：它的入口九成是 MQ 消费，HTTP 只是兜底；
  为兜底路径加第三份过滤器副本，收益低于维护成本。
- 三份 builder / filter 的「必须一起改」是**约定**而非编译期约束（只有常量有测试保护）。

## 4. 备选方案

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 引入 Spring Boot Actuator + OTel 全家桶 | **暂缓** | 要引 agent/exporter/采样策略，与当前「先有基本信号」阶段不匹配 |
| 用 Sleuth 自动透传 | **否决** | Boot 3.x 已移除 Sleuth 支持，官方路径就是 Micrometer Tracing |
| 告警用裸计数 + 静态阈值 | **否决** | 见 §2.1，量纲不同不可比 |
| traceId 过滤器下沉到 tf-shared | **否决** | 会把 servlet 依赖扩散到每个服务，破掉「零第三方依赖」这条防线 |
| 用 MDC 的 `copyOfContextMap` 传递到异步线程 | **暂未做** | 当前异步链路（MQ、调度器）本来就没有入站请求上下文，传了也是空值 |

## 5. 落地记录（2026-10-27）

- **已落地并验证**：四处指标（发件箱积压 / 死信计数 / flush 批次与去重 / 消费失败率）
  + traceId 全链路。traceId 做了真启动验证：引擎 18082 端口，带
  `X-Trace-Id: probe-trace-abc123` 请求 → 响应头原样回写、日志出现
  `WARN [probe-trace-abc123]`；不带 header → 返回新生成的 32 位十六进制；
  启动期非请求线程 → `INFO []`（空方括号证明 pattern 生效且未输出 `IS_UNDEFINED`）。
- **明确未做**：RocketMQ DLQ 深度巡检（需 `rocketmq-tools` 的 `DefaultMQAdminExt`，
  本机无缓存且必须连 name-server 验证）。已用消费失败率覆盖「滑向 DLQ 之前」那一段，
  并在 `failure-mode-ledger.md` §5.1 给出 `mqadmin` runbook。
- 指标清单与告警规则片段见 [`docs/architecture/failure-mode-ledger.md`](../architecture/failure-mode-ledger.md) §5。
