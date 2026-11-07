# ADR-0013：内容级 REVIEW/LIMIT 接通下游（引擎降权信号）

- **状态**：已采纳（Accepted，2026-11-06）→ **被 [ADR-0014](0014-content-demote-and-signal-lifecycle.md) 取代**（2026-11-07）
- **所属改进项**：内容安全子系统（关联 0028 内容安全地基 / 0079 P0 全部命中 + 严重度决策 / 0080 不停机动态添加）
- **决策人**：dafei
- **关联 ADR**：[0008](0008-timeline-layering.md)（公域时间线边界，MONITOR 信号最终作用于此处）

> ⚠️ **本 ADR 的结论已被 ADR-0014 修正，请照新 ADR 实现**：① `MONITOR` 是召回层/搜索层的
> **硬剔除**而非「降权」；② `LIMIT` 应发新增的 `DEMOTE` 档（轻降权）而非 `MONITOR`；
> ③ 信号必须补解除闭环。按 `docs/adr/README.md` 修改规则，**本文件历史结论不改**，
> 勘误与偏差以 §5 追加记录与 ADR-0014 为准。

## 1. 背景（Context）

P0 敏感词改造后，写路径统一经 `ContentSecurityService#requireClean` 返回 `ContentVerdict.action()`，取值 `PASS / LIMIT / REVIEW / BLOCK`。但**下游承接是不完整的**：

- `LIMIT` 与 `REVIEW` 是"名义分级、实质还需下游动作"的裁定：REVIEW 需进人工复审队列、LIMIT 需降权。
- 实测缺口：**CAPTION（上传 + 改描述）的 `LIMIT` 完全没被处理**，等同 PASS 被全量发布；上传的 `REVIEW` 虽建了人审任务，但**从不向引擎发降权信号**——审核窗口内内容仍被全量推荐。
- 引擎侧降权信号 `tf:mod:media` 目前**只**由举报/复审处置路径（`ViolationDispositionService`）发出（`MONITOR`/`INTERCEPT`）。敏感词这两条路径与它用的是**两套枚举**（`ModerationAction` vs `Disposition`），彼此没接线。
- 评论级（COMMENT）的 `LIMIT→FOLDED` / `REVIEW→CONTENT_REVIEW` 已在 `CommentService` 接完（本地处理即可，无需牵动整帖）；昵称级（NICKNAME）的 REVIEW/LIMIT 故意硬拒（公开即时展示位无先发后审空间）。两者**不在本决策范围**。

核心矛盾：内容安全已经能"判"，但**判完之后在推荐侧的 consequences 没接上**，等于把"限公域/降权"这一最关键的抖音式处置留在了半空。

## 2. 决策（Decision）

### 2.1 抽一个 fail-open 的下游路由边界 `ModerationDownstreamRouter`

把"内容级 `REVIEW`/`LIMIT` → 引擎降权信号"的策略收敛成独立 `@Service`：

- `routeContentVerdict(postId, verdict)`：`REVIEW` → `publishMediaDisposition(postId, MONITOR, defaultReviewSeverity, MACHINE)`；`LIMIT` → `publishMediaDisposition(postId, MONITOR, defaultLimitSeverity, MACHINE)`；`PASS`/`BLOCK` 直接返回。
- 整段包 `try/catch`（fail-open）：信号是引擎侧的**加速副本**，丢失不影响上传/改描述主流程（引擎 miss 回源 MySQL）。
- `postId` 为空（反查不到/未提供）则跳过——宁可漏发不误发。

**为何独立成类而非内联进 `MediaUploadService`**：① `MediaUploadService` 构造器 23 参、主链极重，无法轻量单测；② 上传（postId 已知）与改描述（仅 mediaId，需 `findPostId` 反查）两处复用同一策略，独立类避免两处各写后漂移（参考 `MediaStatusMachine` 的 CAS 流转锁定经验）。

### 2.2 复用既有的统一信号通道，零改写手

直接调 `ModerationSignalService#publishMediaDisposition`（已存在、已 fail-open），写出的 `tf:mod:media:{postId}=MONITOR` 与举报路径**同键同语义**——引擎侧 `PlayabilityService`/`ReviewSignalClient` 读 `MONITOR` 即为限公域/降权。本决策**不新增 Redis key、不动 `ModerationSignalService`、不动 `ReviewTask`**。

### 2.3 严重度映射配置化（可灰度、可逆）

在 `ContentSecurityProperties`（`turbofeed.content-security.*`）新增四个键：

| 配置键 | 默认 | 语义 |
|---|---|---|
| `review-publish-signal` | `true` | REVIEW 是否发引擎降权信号（先发后审期间限公域） |
| `limit-publish-signal` | `true` | LIMIT 是否发引擎降权信号（边界敏感内容降权） |
| `default-review-severity` | `MID` | REVIEW 映射严重度 |
| `default-limit-severity` | `LOW` | LIMIT 映射严重度（软降权、不扣分） |

关掉任一开关即回退到旧行为（REVIEW 仅建任务 / LIMIT 无动作），**零回归、可紧急止血**。

### 2.4 接线范围

仅在 `MediaUploadService` 的**上传受理**与**改描述**两处，于既有 REVIEW 人审任务块之后各加一行 `routeContentVerdict(...)` 调用。评论级、昵称级、举报处置路径**一律不改**。

## 3. 后果（Consequences）

**正面**
- 敏感词 `REVIEW`/`LIMIT` 内容在推荐侧真正"限公域/降权"，闭环了 P0 改造留下的半空处置。
- 两个开关 + 默认映射让策略**改配置即生效、可灰度、可一键回退**，不需发版。
- 策略被独立单测锁死（8 用例覆盖"发/不发/开关/空键"），不依附 23 参的怪兽类。

**负面 / 成本**
- 每次 REVIEW/LIMIT 裁定多一次 Redis Hash 写（`tf:mod:media`）——但本就是为加速副本设计，且 fail-open，不进主链路 P99 关键路径之外无新增依赖。
- `default-review-severity=MID` / `default-limit-severity=LOW` 是**工程默认值估计**，不是运营校准值；真实降权力度需运营/算法按效果调，属已知待校准项（见 §5）。
- **引擎消费端在 `feed-engine` 另仓**：本决策只在网关侧把信号发出，引擎是否确实按 `MONITOR` 降权需跨仓联调验证（见 §5，未做）。

## 4. 备选方案

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 内联进 `MediaUploadService` 两处 | **否决** | 不可轻量单测；上传/改描述两处策略易漂移 |
| LIMIT 同时摘除公域时间线（`removeFromTimeline`） | **否决（本期）** | 动作过重；引擎 `MONITOR` 消费 + miss 回源已兜底，先发信号、后接引擎即可，互不阻塞 |
| 按敏感词 `category` 细粒度映射 severity | **暂缓** | 先上默认映射，后续可把 `categoryActions` 的语义延伸到 severity；避免首版过度设计 |
| 评论级也发媒体级 MONITOR | **否决** | 单条评论不该牵动整帖降权；评论级本地 `FOLDED` 已闭环 |

## 5. 落地记录（2026-11-06）

- **已落地并验证**：
  - 新增 `ModerationDownstreamRouter` + 单测 8/8 通过（覆盖 REVIEW→MONITOR/MID、LIMIT→MONITOR/LOW、PASS/BLOCK 不发、两开关关闭不发、空 postId/空裁定不发）。
  - `ContentSecurityProperties` 加 4 配置键（含 getter/setter）。
  - `MediaUploadService` 上传 + 改描述两处接线（构造器加 1 参，Spring 自动注入，无测试手动 `new` 该服务故无编译回归）。
  - 网关全量测试 **283 通过 / 0 失败 / 8 跳过**（含 `ArchitectureTest` 架构约束 5 项通过，新类未违规）；等价于 CI `clean verify` 门禁绿。
- **明确未做（诚实边界）**：
  - **引擎侧 `MONITOR` 降权消费联调**：需 `feed-engine` 仓配合验证"召回该帖应被降权、不进公域高热"；网关侧已发信号且 fail-open，引擎未就绪也不影响主流程。
  - **severity 运营校准**：`MID`/`LOW` 为默认估计，待效果数据回调。
  - **LIMIT 摘公域时间线**：本期未做，若联调发现引擎漏读 MONITOR 再补（低成本）。

## 6. 后续核对发现（2026-11-07，增量事实；结论见 ADR-0014）

对同仓模块做只读核对后，确认本 ADR 的若干判断与事实有偏差，逐条记录以便追溯（**不改写上文结论**）：

| 上文位置 | 偏差 |
| --- | --- |
| §3 负面 / §5 未做 | `tf-feed-engine` **不是另仓**，是同一 `turbo-feed` Maven 仓库的模块，本仓即可编译与单测验证；「需跨仓联调」的前提不成立 |
| §1 / §2.2 / §2.3 | `MONITOR` 在 `ReviewSignalClient.blockedContentKeys` 与 `SearchService.visibleByContent` 中是**硬剔除**；真正的排序降权是 `healthScaleOf` 按 `tf:mod:account.tier=DEMOTE` 的作者级系数。因此「LIMIT 也发 MONITOR」把最轻档处置成了与 REVIEW 等同的整帖隔离 |
| §2.1 / §3 | `MediaUploadService` 改造前构造器为 22 参、加依赖后 23 参，上文写成「构造器 23 参」是改造后的口径，用来论证「为何抽类」时写反了 |
| §2.4 接线范围 | 只接了旧 `upload` 与 `updateCaption`；前端主路径 `presign → complete → MediaUploadFinalizer` 未接，REVIEW/LIMIT 信号在直传链路完全没产生 |
| 全局 | `tf:mod:media` **只有写入没有清除**，人审判无违规后信号随 30 天 TTL 继续硬剔除内容，属静默失效 |

以上 5 项由 [ADR-0014](0014-content-demote-and-signal-lifecycle.md) 修正：新增内容级 `DEMOTE` 档、
补齐解除闭环、接通直传主路径，并在引擎侧实现内容级轻降权。本文作为历史决策快照保留。
