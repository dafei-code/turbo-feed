# ADR-0014：新增内容级 DEMOTE 档与审核信号解除闭环（取代 ADR-0013）

- **状态**：已采纳（Accepted，2026-11-07）
- **所属改进项**：内容安全子系统 / 推荐侧审核信号消费（关联 0013、0008、0028、0079、0082）
- **决策人**：dafei
- **取代**：[ADR-0013](0013-content-review-limit-downstream.md)（其结论中的处置档位与信号语义由本 ADR 修正）
- **关联 ADR**：[0008](0008-timeline-layering.md)（公域时间线边界）、[0005](0005-contract-versioning.md)（跨服务契约变更纪律）

> 本 ADR 涉及**跨服务契约变更**（`tf:mod:media.action` 新增枚举值，网关生产、引擎/搜索消费），
> 按 `docs/adr/README.md` 修改规则：不改 0013 结论，只在其头部标注被取代并追加落地记录。

## 1. 背景（Context）

ADR-0013 落地后对**同仓模块**做只读核对，发现 4 个实质问题（前 2 个是设计缺陷，第 3 个是接线缺口，第 4 个是文档事实错误）：

### D1 — `tf:mod:media` 只有写入、没有清除路径（静默失效）

`MediaProperties.Signal.mediaTtlSeconds = 2592000`（30 天）。上传 REVIEW 写 `MONITOR` 后：

- `MediaReviewService.decideReviewTask(takedown=false)` 判无违规时，只 `resolveAllForMedia` / `reportRepository.resolve` / 记日志，**不删信号**；
- 于是内容即使人审通过、状态 APPROVED、时间线已投递，仍被
  `ReviewSignalClient.blockedContentKeys`（召回层）与 `SearchService.visibleByContent`（搜索层）**硬剔除满 30 天**。

表现是「审核通过了却搜不到、也进不了推荐」，且无任何报错——典型的静默失效。

### D2 — LIMIT 套 MONITOR 处置过重

`ModerationAction` 四级为 `PASS(0)/LIMIT(1)/REVIEW(2)/BLOCK(3)`，LIMIT 是**最轻的非 PASS 档**（COMMENT 场景只做 `CommentStatus.FOLDED`）。但 ADR-0013 让 CAPTION 的 REVIEW 与 LIMIT **都发 MONITOR**，而 `MONITOR` 在引擎召回层与搜索层是硬剔除。结果是 LIMIT 与 REVIEW 处置力度完全相同，且比评论级 FOLDED 重得多。

顺带纠正一处语义混淆：真正做「排序降权」的是 `ReviewSignalClient.healthScaleOf` 按 `tf:mod:account.tier=DEMOTE`（**作者级**）乘 `demoteScale=0.5`；**内容级原本没有任何降权档**。

### D3 — 前端主路径（预签名直传）根本没接下游

`MediaUploadService` 有两条上传链路：旧 `upload(...)`（multipart）与 `presign(...) → complete(...) → MediaUploadFinalizer`。ADR-0013 只在 `upload` 与 `updateCaption` 接了 `routeContentVerdict`。而前端已改造为预签名直传（changelog 0025/0036），`presign` 只调 `requireClean` 做「拦不拦」门禁、`complete` 与 `finalizeAsync` 都不发信号——**主路径的 REVIEW/LIMIT 完全没接**。

### D4 — ADR-0013 / changelog 0082 的事实错误

| 原文 | 事实 |
| --- | --- |
| 「引擎消费端在 `feed-engine` 另仓，需跨仓联调」 | `tf-feed-engine` 是**同一 `turbo-feed` Maven 仓库**的模块，本仓即可验证 |
| 「`MONITOR` 即限公域/降权」 | `MONITOR` 在召回层与搜索层是**硬剔除**；降权是作者健康分 `healthScaleOf` |
| 「`MediaUploadService` 构造器 23 参」 | 改造前为 22 参，ADR-0013 加 1 个依赖后为 23 参；口径写反了 |

## 2. 决策（Decision）

### 2.1 新增 `Disposition.DEMOTE`，明确跨服务消费契约

在网关 `Disposition` 枚举新增 `DEMOTE`（**内容级**轻降权，与 `tf:mod:account.tier=DEMOTE` 的**作者级**降权不是一回事）。四处消费行为一次性定死，写进枚举 Javadoc：

| 层 | INTERCEPT | MONITOR | DEMOTE |
| --- | --- | --- | --- |
| 推荐召回层（`blockedContentKeys`） | 剔除 | 剔除 | **不剔除** |
| 搜索（`SearchService.visibleByContent`） | 剔除 | 剔除 | **不剔除** |
| 播放前复校（`interceptedContentKeys`） | 打断 | 不打断 | **不打断** |
| 精排（`RecallSupport#scoreOf`） | — | — | **乘 `contentDemoteScale`** |

新增档位时这四处必须一起确认，否则会出现「信号写了但没人认」或「被误当放行/误下架」。

### 2.2 两档分流：REVIEW → MONITOR，LIMIT → DEMOTE

| 裁定 | 信号 | 严重度 | 引擎行为 | 解除时机 |
| --- | --- | --- | --- | --- |
| REVIEW | `MONITOR` | `MID` | 召回层/搜索层硬剔除（先发后审的公域隔离） | `CONTENT_REVIEW` 人审判无违规 |
| LIMIT | `DEMOTE` | `LOW` | 精排乘 `contentDemoteScale=0.7`；仍可进公域、可搜、可播 | 改描述后裁定 PASS |

`0.7` 刻意比作者级 `demoteScale=0.5` 温和，与「LIMIT 最轻」对齐；系数与开关都外置到 `FeedModerationProperties`（带默认值，**不改 yml**）。

### 2.3 解除闭环：严格区分「谁有权解除哪一类信号」

新增两个信号服务方法：

- `clearMediaDisposition(postId)`：**整键删除**，用于人审判无违规；
- `clearDemotionIfPresent(postId)`：**仅当 `action=DEMOTE` 才删**，用于改描述 PASS。

调用矩阵（这是本 ADR 最关键的安全边界）：

| 路径 | 动作 | 理由 |
| --- | --- | --- |
| `CONTENT_REVIEW` 人审判无违规 | 无条件解除 | 人审已出结论，MONITOR 的隔离目的已消失 |
| 改描述裁定 PASS | **只**条件解除 DEMOTE | MONITOR/INTERCEPT 属人审/举报处置结论，不是「文本质量」，改一次文案不该能绕过人审隔离甚至下架 |
| **自动审核 APPROVED** | **不解除** | `handleUploaded` 的先发后审正靠 MONITOR 做审核窗口隔离，提前清掉等于放行待复核内容 |
| 改描述 REVIEW/LIMIT | **只升级不降级** | 现存 MONITOR/INTERCEPT 时保留原信号，不覆盖成 DEMOTE |
| 改描述 BLOCK | 不动信号 | `requireClean` 已抛异常，正常不可达 |

### 2.4 三类接线，全部零构造器变更

上一轮 CI 编译断点的根因是「给被测试手动 `new` 的服务加构造器参数」。本轮核对确认
`MediaReviewService`（19 参）、`ViolationDispositionService`（11 参）、`MediaUploadService`（23 参）、
`RecallSupport`（7 参）、`ReviewSignalClient`（3 参）**全部被单测手动构造**，因此：

- 人审解除 → 经已有的 `ViolationDispositionService`（本就持有 `ModerationSignalService` + `mediaRepository`）加方法；
- 改描述解除 → 经已有的 `ModerationDownstreamRouter`（本就持有 signalService）加 `routeCaptionUpdate`；
- 直传主路径 → 在 `complete()` 里用已有的 `contentSecurityService.check(...)` 纯检测重算裁定并路由（`claim` 已保证幂等），**不动 `MediaUploadFinalizer`**；
- 引擎降权 → `RecallSupport` 已有的 `reviewSignalClient` / `moderationProperties` 足够，`RecallContext` 加一个缓存字段即可。

**所有生产类构造器参数零新增**，测试无需改构造调用。

### 2.5 引擎实现：懒读 + 本页缓存，而非批量预取（对原计划的修正）

原计划「批量算 `demotedPosts` 塞进 `FeedFilterContext`」。读码后发现**时序不成立**：
`RecallSupport#scoreOf` 不只在 `RecommendationPipeline` 的池内切片排序（第 348 行）被调用，
各召回通道内部的 `sortByScore` 也会调用，而那些调用发生在 `FeedFilterContext` 构造（第 385 行）**之前**；
批量预取必然漏掉通道内排序这一层，导致轻降权只对部分召回位生效。

改为与作者级 `healthScaleOf` **完全对称**的方案：

- `ReviewSignalClient.contentDemotionScaleOf(postId)`：单 key 懒读，DEMOTE → `contentDemoteScale`，其余/缺失/异常/开关关闭 → `1.0`（fail-open）；
- `RecallContext.contentDemotionCache`：按 `timelineKey` 本页缓存，保证一页内每帖只打一次 Redis；
- `RecallSupport#scoreOf`：`healthScale * contentScale` 作为「审核综合降权系数」传入 `RankingFeatures.authorHealthScale`（**字段名不改**，避免改动精排模型契约与全部既有测试，Javadoc 已说明语义扩展）；
- **`RecommendationPipeline` 与 `FeedFilterContext` 零改动**——降权是排序输入，不是过滤输入。

### 2.6 搜索与播放：确认零改动

`SearchService.visibleByContent` 只拒 `INTERCEPT`/`MONITOR`，DEMOTE 天然落到 `return true`；
`PlayabilityService` 只打断 `INTERCEPT`。二者**刻意不加 DEMOTE 分支**，仅补注释说明「轻降权内容仍可搜/可播」，避免后来者误以为搜索也要跟着降权。

### 2.7 `ViolationDispositionService` 的 DEMOTE 防御分支

`DispositionPolicy` 的配置矩阵可被运维显式配成 `DEMOTE`。原 `applyDisposition` 除 `MONITOR` 外一律落
`takeDownAndPenalize`（下架+扣分+落处罚）——若不拦截，一条只需降权的内容会被误下架。
新增分支：DEMOTE **只写内容信号**，不扣健康分、不摘时间线、不落处罚。默认矩阵不产出 DEMOTE，故既有违规路径零行为回归。

## 3. 后果（Consequences）

**正面**
- REVIEW 与 LIMIT 的处置力度真正分层：前者公域隔离、后者仅精排靠后，与 `ModerationAction` 四级语义一致。
- 人审通过后信号被解除，消除「审核通过却 30 天不可见」的静默失效。
- 直传主路径接通，方案不再只覆盖遗留 multipart 链路。
- 改描述只升级不降级、PASS 只条件解除 DEMOTE，堵住「改文案绕过人审隔离」的口子。
- 引擎/搜索/播放三处对新档位的反应由契约表 + 单测锁定；两个独立开关可分别灰度/回滚。
- 零构造器变更，规避了上一轮 CI 编译断点的同类回归。

**负面 / 成本**
- **跨服务契约变更**：`Disposition` 新增枚举值。灰度期若先上网关、后上引擎，旧引擎的
  `blockedContentKeys` 只认 INTERCEPT/MONITOR → DEMOTE 被当作「无硬处置」放行，**退化成不降权但可见**（fail-safe 方向，可接受）；反之上引擎先上网关则 DEMOTE 永不出现。发布顺序：**网关先、引擎后**，且两者都不需要数据迁移。
- 每次 `scoreOf` 对未见过的 postId 多一次 Redis `HGET`（本页缓存后每帖一次）；开关 `enable-content-demotion=false` 可完全消除该开销。
- `clearDemotionIfPresent` 的「读 + 删」非原子：极端并发下（读到 DEMOTE 后另一线程升级为 MONITOR）可能误删一次，后果仅是少一次隔离，下一跳审核/举报会重写信号。已写入方法 Javadoc。
- `0.7` / `LOW` / `MID` 仍是**工程默认值**，需运营按效果数据校准（沿用 ADR-0013 的待校准项）。
- `complete()` 在 `finalizeAsync` 之前发信号：若异步落库失败，会留一条指向不存在帖子的孤儿信号；因无内容进入候选集，且 30 天 TTL 自动到期，实际无影响。

## 4. 备选方案

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| A：只补清除路径，LIMIT 继续发 MONITOR | **否决** | D2 未修，LIMIT 仍被整帖硬剔除，与四级动作语义矛盾 |
| B：新增 DEMOTE + 引擎内容级降权 + 解除闭环（本决策） | **采纳** | 处置力度、生命周期、主路径接线一次性闭环；跨模块但同仓可验证 |
| C：LIMIT 完全不发信号（撤掉 ADR-0013 的 LIMIT 分支） | **否决** | 退回「CAPTION 的 LIMIT 等同 PASS」，P0 敏感词分级形同虚设 |
| D：批量预取 `demotedPosts` 塞 `FeedFilterContext` | **否决** | 见 §2.5：漏掉召回通道内部排序，且需改动编排时序，风险高于收益 |
| E：给 `MediaReviewService` 直接注入 `ModerationSignalService` | **否决** | 该服务被 4 个单测手动 `new`（19 参），加参即重演 CI 编译断点 |
| F：改描述 PASS 无条件清整键 | **否决** | 作者改一次文案即可绕过 REVIEW 的公域隔离，属安全回退 |

## 5. 落地记录（2026-11-07）

**网关侧**
- `Disposition` 新增 `DEMOTE`（含四处消费契约表）；`DispositionPolicy` / `ContentSecurityProperties` 注释勘误。
- `ModerationSignalService`：新增 `clearMediaDisposition` / `clearDemotionIfPresent`，均 fail-open。
- `ModerationDownstreamRouter`：`LIMIT → DEMOTE`、`REVIEW → MONITOR`；新增 `routeCaptionUpdate`（只升级不降级）。
- `MediaUploadService`：L292 等过时注释修正；`updateCaption` 改走 `routeCaptionUpdate`；`complete()` 补直传主路径接线（带 try/catch fail-open）。
- `MediaReviewService.decideReviewTask`：`CONTENT_REVIEW` 判无违规时经 `disposition.clearContentSignal(...)` 解除；`ViolationDispositionService` 加该方法 + DEMOTE 防御分支。
- `SearchService`：零逻辑改动，补 DEMOTE 可见性注释。

**引擎侧**
- `FeedModerationProperties`：`enable-content-demotion=true` / `content-demote-scale=0.7`（带默认值，不改 yml）。
- `ReviewSignalClient`：`contentDemotionScaleOf` + `contentDemotion` 维度指标；`blockedContentKeys` / `interceptedContentKeys` 明确不含 DEMOTE。
- `RecallContext`：`contentDemotionCache`；`RecallSupport#scoreOf`：作者级 × 内容级相乘；`RankingFeatures` Javadoc 说明语义扩展。
- `FeedFilterContext` / `RecommendationPipeline` 编排零改动（仅注释）。

**验证**
- 双模块 `mvn -pl tf-gateway,tf-feed-engine -am -Pci test`：**网关 302 通过 / 0 失败 / 8 跳过，引擎 222 通过 / 0 失败 / 0 跳过**，两个 `ArchitectureTest`（5/5、3/3）通过，`BUILD SUCCESS`。
- 新增/扩充单测：`ModerationDownstreamRouterTest` 18 例（含「LIMIT 绝不发 MONITOR」「改描述不降级」回归锁）、
  `ModerationSignalServiceTest` 19 例（含解除闭环与条件解除）、`ViolationDispositionServiceTest` 5 例（含 DEMOTE 防御分支）、
  `ReviewSignalClientTest` 13 例（含 DEMOTE 不拦/不打断/系数）、
  **新增** `RecallSupportContentDemotionTest` 5 例（锁死 `0.5 × 0.7 = 0.35`、开关关零 Redis 读、本页缓存只打一次）。
- ADR-0013 头部标注被取代 + §5 追加偏差记录；changelog 0083 单独记录，0082 历史快照未改。

**明确未做（诚实边界）**
- **未真启动联调**：本轮是编译 + 全量单测门禁，未在本地起网关/引擎/Redis 集群做端到端（跨服务契约的运行时验证仍待做）。
- **举报处置链路的解除**：`softLimit` 写 `tf:media:disposition:{postId}=MONITOR` 并 `removeFromTimeline` 摘时间线；人审判无违规只清了 `tf:mod:media`，**没有重投时间线**。该路径属 P0-a 举报处置域，不在本次 REVIEW/LIMIT 范围；症状是「搜索恢复可见、发现流仍缺该帖」。
- **申诉翻案（`handleAppeal(upheld=true)`）未清 INTERCEPT 信号**：同属举报/下架域，内容恢复 APPROVED 后仍被 KV 挡着，登记为后续独立修复项。
- **severity / 系数运营校准**：沿用工程默认值。
- `clearDemotionIfPresent` 的读删竞态（§3）未做 Lua 原子化，评估为低频低危。
