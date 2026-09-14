# 0023 · 修整帖审核偶发 50000：把「读后直写」改为 CAS + 有界重试

> 日期：2026-09-14
> 范围：审核状态机写路径的并发正确性。不改变任何对外接口签名、响应结构与状态语义。
> 前置：`0022-multi-image-post.md`（本缺陷由该功能引入的整帖多行 UPDATE 放大暴露）

## 一、现象

`POST /api/admin/media/review` 偶发返回 `50000`（系统内部错误）。不是必现——
在 0022 的端到端自测中 21 项里出现 1 次失败（20/21），重跑又不一定复现，属典型并发窄竞态。

## 二、取证

### 1. 数据库侧：InnoDB 死锁
`SHOW ENGINE INNODB STATUS` 的 `LATEST DETECTED DEADLOCK` 段落显示：**两个事务执行的是完全相同的
整帖 UPDATE**，互相等待：

```
UPDATE media SET status = 1 WHERE user_id = ? AND post_id = ?
```

加锁顺序相反——一个持 `idx_user_post(user_id, post_id, seq)` 的锁等 `PRIMARY`，
另一个持 `PRIMARY` 的锁等 `idx_user_post`。整帖更新要在**二级索引与聚簇主键之间往返加锁**，
两个事务的往返次序一旦相反即构成环路。

### 2. 代码侧：谁在和谁并发
`MediaReviewService#review` 是「先 `getStatus` 读、再 `updateStatus` 写」的
**检查-后-执行（TOCTOU）**，两步之间没有任何互斥。同一帖存在两条并发写路径：

| 路径 | 触发 | 说明 |
|---|---|---|
| 异步「先发后审」 | 上传后 `handleUploaded` | `AccountCreditService.ensure` 对**无信用记录**的账号默认 **L1**，故直接 `review(..., true)` 置 APPROVED（实测 0.5s 内生效） |
| 管理员人工审核 | `reviewByMediaId` | 运营在审核工作台点击通过 |

两者若都在对方提交前读到 `PENDING`，就会各自发起一次整帖 UPDATE → 双写 → 死锁。
**单行更新时这只是被串行化**（后到的看见状态已变、走幂等返回），
一帖多图把更新放大成 1..9 行，才把它升级成死锁。

## 三、修复

### 1. CAS：让并发双写收敛为「一次生效 + 一次幂等返回」
新增 `MediaJdbcRepository#updateStatusCas(mediaId, userId, expected, target)`：
SQL 带上 `AND status = 期望前置态`，**用影响行数判定是否真正流转**。

```sql
UPDATE media SET status = ? WHERE user_id = ? AND post_id = ? AND status = ?
```

返回 0 行不再是错误，而是「帖子已被另一条路径流转」的正常结果 → 本次不改任何数据、原样返回。
这同时**收窄了加锁范围**到真正需要改的行。

### 2. 有界重试：消化残余的锁冲突
CAS 收窄了窗口但**没有消除锁序反转**（两条语句仍会按相反次序碰同一批索引记录）。
故整帖 UPDATE 统一走新入口 `updatePostRows`，捕获 `PessimisticLockingFailureException`
做**有界重试**（3 次、15ms × 尝试次数退避）：

- MySQL 对该错误的官方处置就是 *"try restarting transaction"*；单条 UPDATE 死锁时**整条回滚**、
  不留半更新，所以重试是安全且幂等的。
- 退避总量 < 100ms，避免在真实高争用下把请求线程拖长。
- **耗尽后原样抛出**，不做无界重试去掩盖问题。

### 3. `ReviewOutcome`：区分「结果状态」与「是否由我流转」
`review()` 由返回 `MediaStatus` 改为返回 `record ReviewOutcome(MediaStatus status, boolean transitioned)`。

**这是防重复投递的关键**：调用方在状态变 APPROVED 后要投递公域时间线。
若只看 `status == APPROVED`，那么「CAS 落空、帖子刚被别人审过」会被误判成「我审的」，
同一条帖子被投递两次。`transitioned = false` 时调用方**必须放弃投递**——流转方自己会投。

### 4. 删除非 CAS 写入口
原 `updateStatus(mediaId, userId, status)`（`mediaId` 派发便捷方法）在迁移后已无调用方。
它是**不带 CAS 的整帖写入口**，留着等于把同一个 bug 的入口重新摆回原位，故删除；
保留 `updateStatusByPost` 作为显式盲写原语，并在 javadoc 写明使用边界
（仅限状态机之外的一致性修复/补偿，审核流转一律走 CAS）。

### 5. CAS 推广到其余「读后写 + 非幂等副作用」的流转
同类 TOCTOU 在举报/申诉闭环里同样存在，各自的副作用都**不是幂等操作**：

| 位置 | 前置态 → 目标态 | 不加 CAS 的后果 |
|---|---|---|
| 高危举报下架 | APPROVED → TAKEN_DOWN | 同一违规被多人同时举报 → **信用被重复扣减** |
| 管理员确认举报 | APPROVED → TAKEN_DOWN | 高危举报已先行下架时再次扣分 |
| 作者申诉 | REJECTED/TAKEN_DOWN → APPEALING | 并发重复提交 → **重复写申诉单** |
| 管理员处理申诉（翻案） | APPEALING → APPROVED | 重复点击 → 时间线重复投递 + **信用重复加回** |

均改为「CAS 命中才执行副作用」。

## 四、验证（含「测试是否空转」的自证）

### 1. 定向并发复现（`/tmp/repro_deadlock.py`，纯标准库）
上传 1 帖 9 图，两个线程**同时**对同一帖发起「审核通过」。**连续 7 轮，每轮 2/2 成功**。

### 2. 日志取证：证明死锁真实发生、且被正确吸收
第 1 轮日志同时出现以下三类记录——这是本次修复最关键的证据：

```
WARN  MediaJdbcRepository : 整帖更新遇锁冲突（第 1/3 次尝试），退避后重试:
      ... WHERE user_id = ? AND post_id = ? AND status = ?;
      Deadlock found when trying to get lock; try restarting transaction     ← exec-5
WARN  MediaJdbcRepository : 同上                                              ← exec-1
INFO  MediaReviewService  : 审核状态流转（整帖）: PENDING -> APPROVED, rows=9  ← review-async-3
INFO  MediaReviewService  : 审核 CAS 落空（已被其他路径流转，本次幂等返回）:
      expected=PENDING, actual=APPROVED                                      ← exec-5
INFO  MediaReviewService  : 审核 CAS 落空（已被其他路径流转，本次幂等返回）:
      expected=PENDING, actual=APPROVED                                      ← exec-1
```

逐条解读：
- 出现 `Deadlock found` → **死锁确实发生了**，证明测试命中的是真实并发窗口，**不是空转**；
- 重试后拿到 0 行并走到「CAS 落空」→ **有界重试吸收了这个死锁**，没有冒泡成 50000；
- 异步路径 `rows=9` 胜出 → 一次生效；两个管理员线程 `expected=PENDING, actual=APPROVED`
  → 各自幂等返回，**都没有重复投递时间线**。

### 3. 全局无残留
全程日志中**没有**「放弃重试」、没有 50000、没有未捕获异常。
仅有的两条非死锁记录是既有的 `sensitive_word` 表缺失（启动期非致命 WARN）
与 10 张超限上传的**预期**拒绝（`42902`）。

### 4. 端到端回归
`0022` 的 21 项端到端**全部通过（21/21）**，此前唯一失败项即本缺陷。

## 五、遗留边界（诚实说明）

- **有界重试是「围堵」不是「根治」**：极端争用下 3 次重试仍可能耗尽并抛 50000。
  真正的根治是让两条写路径**收敛为单一写者**——例如先发后审与人工审核共用一条状态机命令队列，
  或对「状态流转」建数据库层唯一约束/幂等键。当前方案是在不改架构的前提下，把
  「偶发死锁」降级为「极小概率的可重试失败」。
- 管理端审核队列仍会展示**已被异步路径流转过**的帖子（列表是快照），
  审核员点下去会得到幂等成功——语义正确但交互上应做可见性收敛（列表端过滤非 PENDING）。
- 15s 推荐流旁路缓存下，下架为最终一致（既定取舍，本次未改）。

## 六、影响文件
- `tf-gateway/.../repository/MediaJdbcRepository.java`
  —— 新增 `updateStatusCas` / `updatePostRows`（有界重试）/ `sleepQuietly`；删除 `updateStatus`；
  `updateCaption` / `updateStatusByPost` / `delete` / `hardDeleteByPost` 统一改走带重试的入口。
- `tf-gateway/.../service/review/MediaReviewService.java`
  —— 新增 `ReviewOutcome`；`review` 改 CAS；4 处举报/申诉流转改 CAS 并保护其非幂等副作用；
  时间线投递条件由「结果状态」改为「本次是否真正流转」。
