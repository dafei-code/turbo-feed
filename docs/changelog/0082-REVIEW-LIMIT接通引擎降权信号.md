# 0082 · 内容级 REVIEW/LIMIT 接通下游（引擎降权信号）

- **状态**：已完成（网关单测 8/8 + 全量 283 通过 / 0 失败 / 8 跳过，2026-11-06）
- **优先级**：P1 收尾（补齐 P0 敏感词改造遗留的"判完不接推荐"半空处置）
- **关联**：0028（内容安全地基）、0079（P0 全部命中 + 严重度决策）、0080（不停机动态添加）、ADR-0013

## 为什么做

P0 敏感词改造把写路径统一到 `ContentSecurityService#requireClean`，返回 `REVIEW`/`LIMIT`/`BLOCK`/`PASS` 裁定。但实测下游承接有缺口：

- **CAPTION 的 `LIMIT` 完全没处理**，等同 PASS 被全量发布（边界敏感描述照常进推荐）。
- **CAPTION 的 `REVIEW` 只建了人审任务、从不向引擎发降权信号**，审核窗口内内容仍被全量推荐。
- 引擎降权信号 `tf:mod:media` 此前只由举报/复审处置路径（`ViolationDispositionService`）发出；敏感词路径用的是另一套枚举 `ModerationAction`，两者没接线。

抖音式处置的核心是"判完之后在推荐侧有限公域/降权"，这一步此前是缺失的。

## 改动

| 文件 | 改动 |
| --- | --- |
| `tf-gateway/.../service/signal/ModerationDownstreamRouter.java` | **新增**：fail-open 路由边界。`routeContentVerdict(postId, verdict)` 把内容级 `REVIEW`/`LIMIT` 转成 `ModerationSignalService.publishMediaDisposition(postId, MONITOR, severity, MACHINE)`；`PASS`/`BLOCK`/空键/空裁定直接返回；整段 `try/catch` 不影响主流程 |
| `tf-gateway/.../service/moderation/ContentSecurityProperties.java` | 加 4 个配置键：`review-publish-signal`(默认 true) / `limit-publish-signal`(默认 true) / `default-review-severity`(默认 MID) / `default-limit-severity`(默认 LOW)，含 getter/setter |
| `tf-gateway/.../service/MediaUploadService.java` | 构造器加 `ModerationDownstreamRouter` 1 参（Spring 自动注入）；**上传受理**与**改描述**两处在既有 REVIEW 人审任务块之后各加一行 `routeContentVerdict(...)` 调用（改描述路径用 `mediaRepository.findPostId` 反查 postId） |
| `tf-gateway/.../service/signal/ModerationDownstreamRouterTest.java` | **新增**：8 用例锁死策略（REVIEW→MONITOR/MID、LIMIT→MONITOR/LOW、PASS/BLOCK 不发、两开关关闭不发、空 postId/空裁定不发） |
| `docs/adr/0013-content-review-limit-downstream.md` | **新增**：架构决策记录（背景/决策/后果/备选/落地记录，含负面成本与未做边界） |
| `docs/adr/README.md` | 索引表追加 0013 一行 |

### 设计取舍（对应 ADR-0013）

- **独立路由类而非内联**：`MediaUploadService` 构造器 23 参、主链极重，内联则不可轻量单测、且上传/改描述两处易漂移。
- **复用既有 `tf:mod:media` 通道、零改写手**：与举报路径同键同语义，引擎侧 `PlayabilityService`/`ReviewSignalClient` 读 `MONITOR` 即限公域/降权。
- **不改的范围**：评论级（`CommentService` 已本地 `FOLDED`/人审闭环）、昵称级（故意硬拒）、举报处置路径、信号服务、复审任务表——一律不动，零行为回归。
- **两个开关 + 默认 severity**：改配置即生效、可灰度、可一键止血。

## 验证

| 验证项 | 方法 | 结果 |
| --- | --- | --- |
| 路由策略单测 | `ModerationDownstreamRouterTest` | 8/8 通过 |
| 网关全量回归 | `mvn -pl tf-gateway -Pci test`（等价 CI `clean verify`） | 283 通过 / 0 失败 / 8 跳过（Docker 集成测本地跳过） |
| 架构约束 | `ArchitectureTest` | 5/5 通过（新类未违反 `tf-shared` 零三方依赖等规则） |
| 编译门禁 | 全模块 main + test 源码编译 | 通过（新增构造参数无调用点手工 `new`，无编译回归） |

## 未做留白（诚实边界）

- **引擎侧 `MONITOR` 降权消费联调**：需在 `feed-engine` 仓验证"召回该帖应被降权、不进公域高热"。网关侧已发信号且 fail-open，引擎未就绪也不影响主流程——可先发信号、后接引擎。
- **severity 运营校准**：`MID`/`LOW` 为工程默认估计，非运营校准值，待效果数据回调。
- **LIMIT 摘公域时间线**：本期未做；若联调发现引擎漏读 `MONITOR` 再补（低成本）。
