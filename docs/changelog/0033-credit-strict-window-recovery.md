# 0033 加严标记时间窗自动解除 + 信用分自然恢复（P0-5 / P0-6）

- 日期：2026-09-18
- 范围：`tf-gateway`（`AccountCreditRepository` / `CreditRecoveryTask` / `MediaProperties`） + `deploy/mysql/init-local.sql`
- 关联：[内容安全后续方案](../../turbo-feed-内容安全后续方案.html) 阶段一 · P0-5 / P0-6
- 设计拍板：用户「选择1」——认可默认窗口值（加严 30 天 / 观察 7 天 / 恢复 +5 / 静默 30 天）并直接实现。

## 1. 动机

P0-2/P0-8 落地后，加严/观察「是否仍生效」完全依赖持久化标记列：

- `strict_queue_flag=1`（违规加严）只会置 1、从不自动清 0 → 账号一旦违规，**永久先审后放**，无法自然解封。
- `new_user_watch=1`（新人观察期）虽有人审达阈值解除路径，但**仅按帖数计数**，无时间兜底：
  低活跃新号若长期不上传/不通过人审，会卡在观察期无法转正。

这两点违反「惩罚应有时限、观察应有尽头」的基本产品直觉，也与抖音「信用分随良好行为回升」范式不符。
P0-5/P0-6 引入**时间窗推导 + 每日自然恢复**，让加严/观察到期自动解除、信用分随无违规行为缓慢回升。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `deploy/mysql/init-local.sql` | `account_credit_0..3` 四张物理表各加两列：`last_violation_at DATETIME NULL`（P0-5 违规时点）、`watch_since DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`（P0-6 观察期起始）。加列须逐物理表执行（分片表全局连续编号） |
| `config/MediaProperties.java`（`Review` 内部类） | 新增 4 个窗口/恢复配置项（含 getter/setter）：`strictQueueWindowDays=30`、`newUserWatchWindowDays=7`、`creditRecoverStep=5`、`creditRecoverQuietDays=30`，均带无风险默认值 |
| `repository/AccountCreditRepository.java` | ① 注入 `MediaProperties`；② `ensure()` INSERT 写入 `watch_since=NOW()`（新号即进入观察期并打起始时点）；③ `deduct()` 同条 UPDATE 写 `last_violation_at=NOW()`；④ `isStrict(...)` 改造为**时间窗推导**（Java 端 `Instant.minus(Duration)` 算好 cutoff 传参，避开 ShardingSphere 方言差异）；⑤ `getLevel()` 调新签名 |
| `service/review/credit/CreditRecoveryTask.java`（**新建**） | `@Scheduled(cron="0 0 3 * * ?")` 每日凌晨给「近 `creditRecoverQuietDays` 天无违规」账号加分（`creditRecoverStep`，封顶 100），并在同条 UPDATE 内按新分重算 `level` + 顺手清过期 `strict_queue_flag`，使数据自洽 |

## 3. 关键决策

- **正确性兜底 = 读取时时间窗推导**：`isStrict` 在每次读等级时实时判断「`strict_queue_flag=1` 且 `last_violation_at` 未超 30 天」或「`new_user_watch=1` 且（`new_user_approved_count` 未达阈值 或 `watch_since` 未超 7 天）」。
  超窗口立即不再加严，**杜绝永久加严**，无需依赖任何定时任务跑过。
- **cutoff 在 Java 端算**：MySQL 方言 `NOW() - INTERVAL ? DAY` 经 ShardingSphere 改写易踩坑，故在 Java 用 `Instant.minus(Duration.ofDays(n))` 算好 `Timestamp` 传参；`NULL` 的 `last_violation_at` 视为已过期（即非加严）。
- **定时恢复只管「分数回升」**：`CreditRecoveryTask` 与读取推导**正交**——窗口到期解除的是「加严标记」，分数恢复才决定能否回到 L1/L2 高信用池。二者职责分离，互不影响正确性。
- **`CreditRecoveryTask` 的 SQL 修正（自审发现）**：初版用 `strict_queue_flag=0` 过滤 + 只加分不重算 `level`，会导致超窗口的违规账号被永久排除在恢复外、且 `level` 列停在违规时的 0。
  修正为：去掉 flag 过滤（改按 `last_violation_at` 窗口判断）、同条 UPDATE 加分 + 按新分重算 `level` + 清过期 `strict_queue_flag`；保留 `new_user_watch=0`（新号分数本就 100）。
- **分片友好**：恢复任务 UPDATE 不带分片键 → ShardingSphere 广播到 4 张物理表，全量覆盖；每日一次低频，不影响在线写。
- **可关**：注释 `CreditRecoveryTask` 的 `@Scheduled` 即停用，不影响读取推导的正确性（窗口解除独立生效）。

## 4. 验证

- 编译：`mvn -pl tf-gateway -am compile` 通过（exit 0）。
- 逻辑核对：
  - `isStrict` 时间窗推导在 `getLevel` 每次调用生效，超窗口即降级；
  - `deduct` 写 `last_violation_at` → 30 天后 `isStrict` 自动返回 false；
  - `ensure` 写 `watch_since` → 7 天 + 人审达阈值后 `onHumanApproved` 置 `new_user_watch=0`（或纯超时也算解除）；
  - `CreditRecoveryTask` 加分后按新分重算 `level`，分数回 100 即 `level` 回 2（L2 高信用池）。

## 5. 遗留

- `CreditRecoveryTask` 移除的是「过期」`strict_queue_flag`；活跃违规账号的 flag 仍保留至窗口到期由读取推导跳过，持久态仅在该账号进入恢复窗口时清理——无功能影响。
- 恢复节奏（步长/静默天数）与窗口（加严/观察天数）均为可调配置，后续按线上数据微调。
- 阶段二~四（白名单 admin 端点 / stats 端点 / 同形归一 / 拼音谐音 / P0-3 outbox / P0-7 128 表）尚未启动。
