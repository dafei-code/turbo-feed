# 0035 处罚域阶段 A：解除通道 + 上传写入拒写闸门

- 日期：2026-09-18
- 范围：`tf-gateway`（`service/penalty` / `service/MediaUploadService` / `config/MediaProperties`）+ `deploy/mysql/init-local.sql`
- 关联：[内容安全后续方案](../../turbo-feed-内容安全后续方案.html) · penalty 阶段 A（[0034](0034-penalty-package-skeleton.md) 的接线第一步）

## 1. 动机

[0034](0034-penalty-package-skeleton.md) 落地的处罚骨架是**死代码**：没有任何端点调用它。且经核对存在一个**阻断性缺口**：

> `PenaltyService` 只有 `recordViolation / getStatus / canWrite / isBanned`，**没有解除方法**；
> `ViolationSource.APPEAL_OVERTURN` 只是枚举、未接任何撤销逻辑。
> → **`BANNED_PERM` 一旦误封，系统里没有任何路径能撤销**（`BANNED_TEMP` 还能靠 `ban_until` 到期自愈，永久封不能）。

封禁是高不可逆操作——「能封不能解」比「不能封」更危险。故阶段 A 先补撤销通道，再开写路径闸门。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `service/penalty/PenaltyService.java` | 新增 `liftPenalty(userId, source, operator, reason)`：写 `LIFT` 反向审计 + 状态回 NORMAL + 清封禁字段 |
| `service/penalty/PenaltyAction.java` | 新增枚举 `LIFT(6)`（解除封禁，非违规动作，仅审计留痕） |
| `service/penalty/ViolationCategory.java` | 新增枚举 `OTHER(0)`——未分类兜底（解除记录类目为空时、以及后续违规类目未映射时使用） |
| `service/MediaUploadService.java` | 注入 `PenaltyService`；新增 `requireWritable(long)` 闸门，拦 4 个写入入口 |
| `config/MediaProperties.java`（`Review`） | `criticalAutoPermBan` 默认 **true → false**（灰度安全值） |
| `deploy/mysql/init-local.sql` | `violation_record_0..3` 的 `category` 注释补 `0其他(未分类兜底)` |

## 3. 关键决策

- **解除只清封禁字段，不重置 `violation_count`**：撤销一次处罚 ≠ 抹掉历史（历史仍在 append-only 的 `violation_record`）。若重置计数，再犯要重新攒够阈值才能封，等于给惯犯发「免罚卡」；保留计数 → 解除后再违规**立即**按累计次数触发封禁。
- **闸门位置：早于限流与幂等**。放后面有两个副作用：① 被封账号仍消耗限流令牌；② 幂等留下 in-progress 记录后才抛异常，重试会先撞 `UPLOAD_IN_PROGRESS` 而非 `FORBIDDEN`，错误语义漂移。
- **4 个写入入口全部拦截**：`upload`(网关收字节) / `presign`(发直传凭证) / `complete`(直传完成落库) / `updateCaption`(改文案)。只拦 `upload` 的话，被封账号仍可走 `presign`+`complete` 直传发布——**这是最容易漏的绕过路径**。
- **刻意不拦 `delete`**：删除自有内容是「减害」操作而非发布，封禁期间仍应允许自行清理；拦了反而把内容锁死在系统里。
- **灰度关永久封**：`criticalAutoPermBan` 默认改 false，最重处置也只是临时封禁 `banTempDays` 天，观察误封率与申诉量稳定后再打开（配合 `liftPenalty` 撤销通道）。
- **`OTHER(0)` 兜底类目**：违规类目暂无映射时的保守选择，避免为落库而错分到具体类目（后续接线 `recordViolation` 时人审驳回只带理由文本，即用此类目）。

### ⚠️ 附带改动：`MediaUploadService` 改为显式构造器

本类原用 `@RequiredArgsConstructor`。改它是**被迫且必要**的：本机构建环境下 Lombok 注解处理对「重新编译的文件」不生效，
表现为全部 final 字段报「未在默认构造器中初始化」——即**本类在该环境下一旦被修改就无法编译**（改动前实测 42 个同类错误）。
为使封禁闸门能落在上传写入的唯一收口，这里改为显式 15 参构造器，与 `service/penalty` 包保持一致。
详见 [0034 §5](0034-penalty-package-skeleton.md)「构建环境说明」。

## 4. 验证

- 编译：`mvn -pl tf-gateway -am compile` **BUILD SUCCESS**（0 error）。
- 落点核对：`upload`(209-210 解析 uid 后即拦) / `presign`(316) / `complete`(414) / `updateCaption`(606) 均已调用 `requireWritable`；`delete` 未拦（符合设计）。

## 5. 遗留（阶段 A 未做）

- **违规确认 → `recordViolation()` 仍未接线**（`MediaReviewService#report` / `handleReport` 内的 `onViolationConfirmed` 旁）。
  故当前**不会有任何账号被封**：闸门已就位但上游不产生处罚。这是有意为之的安全顺序（先有闸门 + 撤销通道，再开处罚）。
  接线前需先定：**违规类目从哪来**（人审驳回只有理由文本，暂无类目映射，暂定 `OTHER`）、严重度如何定。
- **评论路径未拦**：`CommentController#publish` 尚未加 `requireWritable`，下一步补（同属写路径）。
- **P0-3 outbox**：封禁后「清存量内容」仍依赖 fail-open 的事件投递，未补偿。
- 同类计数仍是「累计总数」（阶段 C 改按类目现算）。
