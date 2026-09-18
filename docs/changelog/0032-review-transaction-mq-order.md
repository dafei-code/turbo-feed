# 0032 审核入口事务化 + 时间线投递移到事务提交后（P0-4）

- 日期：2026-09-17
- 范围：`tf-gateway`（`MediaReviewService`）
- 关联：[内容安全后续方案](../../turbo-feed-内容安全后续方案.html) 阶段一 · P0-4

## 1. 动机

`MediaReviewService.reviewByMediaId` 原先**无 `@Transactional`**，且时间线投递
`feedTimelinePublisher.append()` 与「状态 CAS 翻转 + 新人观察期计数」混在同一非事务方法内：

1. **非原子**：若 `review()`（CAS 翻转）成功、但后续 `onHumanApproved`（信用计数）抛异常，
   状态已落库可见而信用计数丢失 → 状态/计数分裂提交。
2. **MQ 先于提交**：时间线在 DB 真正提交前发出，若后续步骤失败回滚，出现
   「消息已发但事务回滚」导致时间线裸奔（内容未过审却进公域）。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `MediaReviewService.java` | `reviewByMediaId` 加 `@Transactional(rollbackFor = Exception.class)`；`feedTimelinePublisher.append()` 改为通过 `TransactionSynchronizationManager.registerSynchronization` 注册到 `afterCommit`——仅当 DB 事务提交成功后才发 MQ/HTTP。投递本身 try-catch 记录 fail-open 残余 |

## 3. 关键决策

- **事务保证 DB 原子**：「状态 CAS 翻转 + 新人观察期计数」在同一事务内，要么都成要么都回滚。
- **afterCommit 保证不裸奔**：时间线只在事务提交后投递，杜绝「消息已发但事务回滚」；
  同时 `asTimelinePost` / `ensure` 的读在事务内取到一致已提交态。
- **fail-open 残余明确边界**：afterCommit 内投递失败（事务已提交不可回滚）属已知残余，
  由阶段三 P0-3（事务发件箱 outbox）补偿，不在此处回滚。

## 4. 验证

- 编译：`mvn -pl tf-gateway -am compile` 通过（exit 0）。
- 逻辑核对：`afterCommit` 回调捕获 `post`/`level`（effectively final），仅事务成功提交后执行；
  投递异常被 catch 并记录告警，不影响已提交事务。

## 5. 遗留

- **P0-3（outbox）**：afterCommit 投递失败的补偿机制尚未实现，属 fail-open 残余。
- 同类「事务内发 MQ」问题在 `handleUploaded` / `handleAppeal` 已随各自 `@Transactional` 存在，
  其时间线投递仍在事务内（fail-closed 取向），本次仅收敛 `reviewByMediaId` 到 afterCommit 语义，
  统一 outbox 化留待 P0-3。
