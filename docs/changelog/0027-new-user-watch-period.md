# 0027 · 新号观察期：新账号默认先审后放，审核口径全链路对齐

> 日期：2026-09-17
> 范围：`tf-gateway`（代码 + 配置 + DDL）、`turbo-feed-ui/review.html`
> 前置：`0023-post-review-deadlock-cas.md`（审核状态机 CAS）、`0025-upload-presigned-direct-and-scale-tuning.md`
> 关联：`docs/architecture/moderation-design.md`（审核体系 P0~P3 路线，本变更为其中的 S1）、`docs/modules/tf-gateway.md`

## 一、背景：对标抖音审核时暴露的两处「说做不一」

复核审核链路是否能对标抖音机制时，发现**文档/注释描述的行为与代码实际行为相反**，两处同源：

| # | 三处文本的说法 | 代码实际行为 |
|---|---|---|
| 1 | `CreditLevel` / `tf-gateway.md` 口径为「L0（低信用/**新号**）→ 先审后放」 | 新账号**无信用记录**，`AccountCreditRepository.ensure` 插入的 `level=1`，`getLevel` 直接返回 **L1 → 先发后审**，即新号内容**直接进公域** |
| 2 | `application.yml` 注释与 `review.html` 文案：「无论哪种机审，默认策略都是**驳回即拦 · 通过仍人审**」 | L1/L2 机审通过即**直接置 APPROVED 进流量池**，根本不经过人审队列 |

问题 1 是**风控口子**：新号（最需要观察的账号）恰恰享受了最宽松的策略，与抖音「新账号有观察期」的设计相反——批量注册小号即可绕过所有人审闸发广告/引流。问题 2 是**认知污染**：运维按注释理解，会误以为「人审是必经闸」，从而低估人审队列的实际负载（实际只承载 L0 流量）。

本次把行为拉齐到设计意图：**新账号默认先审后放，人审通过达阈值后自动转正常分级**；并把注释/文档/前端文案统一到「按信用分级分流」的准确口径。

## 二、为什么新号必须先审后放（而不是「默认 L1 先发后审」）

原实现的默认值选择是「无记录 = 普通用户」，隐含假设「默认信任」。这在 UGC 场景下代价不对称：

| 方向 | 误判代价 |
|---|---|
| 新号先发后审（原实现） | 恶意小号内容**先触达全量用户**，事后下架只是止损；批量注册成本极低 → **错放代价高且可被规模化利用** |
| 新号先审后放（本次） | 正常新用户首发**延迟可见**，代价是体验与审核成本 → **错拦代价低且一次性** |

抖音同向：新账号/异常账号进人工与小流量观察，内容先审后放或仅自己可见；信用积累后放开。**默认从严 + 达标转正**，比「默认从宽 + 违规加严」更符合 UGC 风险模型。

## 三、设计取舍：为什么不复用 `strict_queue_flag`

两条「加严」路径语义不同，**刻意分成两列**：

| 列 | 语义 | 触发 | 解除 |
|---|---|---|---|
| `strict_queue_flag` | **违规加严**（处罚） | 举报成立 / 人审驳回 → `deduct()` | **无自动解除**（待定，需申诉流程） |
| `new_user_watch` | **新人观察期**（信任未建立） | 注册首次写入信用行 | 人审通过达阈值 → 自动置 0 |

若复用同一列，「新人达标转正」会**顺手把被处罚账号一并解封**——处罚随之失效。`getLevel` 改为「任一为真即按 L0 口径」，两条加严路径可独立演进。

阈值由 `turbofeed.media.review.new-user-approve-threshold` 控制（默认 3），按环境可调。

## 四、改动清单

| 文件 | 改动 |
|---|---|
| `repository/AccountCreditRepository.java` | `ensure()` 插入 `new_user_watch=1, new_user_approved_count=0`；`isStrict()` 改为 `strict_queue_flag=1 OR new_user_watch=1`；新增 `onHumanApproved(userId, threshold)`；`findAll()` 补选两列；类注释更正（`account_credit` 为 `autoTables` **分片表**，非单表） |
| `service/review/credit/AccountCredit.java` | record 增 `newUserWatch` / `newUserApprovedCount` 两字段 |
| `service/review/credit/AccountCreditService.java` | 新增 `onHumanApproved(userId, threshold)`，解除时打 INFO |
| `service/review/MediaReviewService.java` | `reviewByMediaId`（**人工**通过分支）追加计数调用 |
| `config/MediaProperties.java` | `Review` 增 `newUserApproveThreshold`（默认 3）+ getter/setter |
| `service/review/credit/CreditLevel.java` | L0 注释更正为「新人观察期 / 违规加严 / 低信用」，并说明 `getLevel` 的强制降级 |
| `src/main/resources/application.yml` | `moderation-mode` 注释改正为「按信用分级分流」；新增 `new-user-approve-threshold: 3` |
| `deploy/mysql/init-local.sql` | **4 张**物理表 `account_credit_0..3` 增两列 |
| `turbo-feed-ui/review.html` | 队列说明改为「本队列只承载先审后放的内容」 |

**「只统计人工通过」是刻意的**：计数调用点放在 `reviewByMediaId`（管理员入口），`handleUploaded` 的先发后审自动通过**不计数**。否则新号第一帖上传即触发 `onHumanApproved`，自己把自己顶出观察期，观察期形同虚设。

**存量账号不受影响**：`ensure()` 的 `ON DUPLICATE KEY UPDATE` 只刷新 `updated_at`，不回写 `new_user_watch`——否则本策略上线会把全部历史账号整体打回先审后放。

## 五、MySQL 赋值顺序取证（决定 SQL 里子句顺序）

MySQL 单表 `UPDATE` 的 `SET` 子句**从左到右求值，后面的赋值能看到前面已改的新值**。实测：

```sql
-- SET a = a+1, b = a  →  b 得到的是 a+1（新值），不是旧值
```

因此 `onHumanApproved` 把依赖**旧计数**的 `new_user_watch` 放在前面、计数自增放后面：

```sql
UPDATE account_credit SET
  new_user_watch = CASE WHEN new_user_approved_count + 1 >= ? THEN 0 ELSE new_user_watch END,
  new_user_approved_count = new_user_approved_count + 1,
  updated_at = ? WHERE user_id = ?
```

反过来写会让阈值判断用上自增后的值，**差 1 帖**（第 3 帖通过时判成已满，或反之）。

> 同一规律也解释了 `deduct()` / `restore()` 的**既有缺陷**（本次未改，见第八节）：它们的 `level = CASE WHEN credit_score - 20 ...` 写在分数赋值**之后**，CASE 看到的是已扣分的新值，等价于按「扣 40 分」判级，**等级滞后一档**。

## 六、验证（端到端，真实 MySQL/Redis/MinIO）

测试实例：`--server.port=8093`、`storage=local`、`workerId=2`（避开本地已占用端口与实例）。

1. 注册新账号 `13911110001`（userId `375869663168700416`）→ `account_credit` 行 `new_user_watch=1, approved_count=0`；
2. 上传第 1 帖 → 日志 `低信用账户：机审通过转人审队列（先审后放，整帖）`，
   DB 状态 `PENDING`；管理员通过 → `APPROVED`，`approved_count=1`；
3. 第 2 帖同上，`approved_count=2`；
4. 第 3 帖通过 → 日志 `新人观察期结束（人审通过达阈值 3）: userId=..., level=L1`，
   行内 `new_user_watch=0, approved_count=3`；
5. 第 4 帖上传 → **直接 `APPROVED`**（先发后审），`new_user_watch` 保持 0。

结论：观察期「准入从严 → 达标转正」闭环成立，且转正只由**人工**通过驱动。

清理：media / account_credit / user 测试行硬删至 0 行，本地存储目录清空，验证实例已停、端口已释放。

## 七、影响与回滚

- **行为变更（对外可见）**：新注册账号的首批内容不再直接进公域，改为人审通过后可见。
  这是**有意为之**，也是本变更的目的；线上若需临时放宽，把 `new-user-approve-threshold` 调小
  （设为 0/1 可近似回到旧行为），无需改代码。
- **DDL 向后兼容**：两列均有 `NOT NULL DEFAULT 0`，存量行自动取 0（= 不在观察期），
  不加 `new_user_watch` 的话老账号不会被误判。**注意反直觉点**：DDL 默认 0 是「不在观察期」，
  而 `ensure()` 的 INSERT 显式写 1 才是「新号入观察期」——新号身份由写入路径决定，不是列默认值。
- **回滚**：代码回滚即可恢复旧行为（列可留在表上，`isStrict` 不再读它）。

## 八、遗留（本次未处理，需另行确认）

1. **`deduct()` / `restore()` 等级滞后一档**：见第五节的赋值顺序分析。已实测确认，
   修正方式是把 `CASE` 挪到分数赋值**之前**；因属行为变更，待确认后处理。
2. **`strict_queue_flag` 无自动解除路径**：被处罚账号永久加严，需申诉/时间窗策略。
3. **观察期计数只增不减**：当前不做「人审驳回则清零重计」，恶意用户可通过「发 3 帖正常内容转正后再违规」
   绕过；彻底方案是把计数与违规记录挂钩（见 `docs/architecture/moderation-design.md` P1）。
4. **观察期无时间窗**：纯「通过 N 帖」转正，不发帖就永远停留观察期（对僵尸号无害，
   但缺少「注册满 X 天且无违规」的第二条转正路径）。
