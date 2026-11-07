# 0083 · 新增内容级 DEMOTE 档与审核信号解除闭环（取代 0082 的处置档位）

- **状态**：已完成（双模块全量门禁绿：网关 302 通过 / 0 失败 / 8 跳过，引擎 222 通过 / 0 失败，2026-11-07）
- **优先级**：P0 修正（0082 的处置力度错误 + 静默失效 + 主路径漏接）
- **关联**：0082、ADR-0013（被取代）、ADR-0014、0008（公域时间线边界）、0005（契约变更纪律）

> 0082 是历史快照，本文件不修改它。勘误见 ADR-0013 §6 与 ADR-0014 §1。

## 为什么做

0082 落地后对同仓模块做只读核对，发现 4 个问题：

| 编号 | 问题 | 影响 |
| --- | --- | --- |
| D1 | `tf:mod:media` **只有写入没有清除**，人审判无违规不删信号 | 内容审核通过、时间线已投递，仍被引擎召回层与搜索层硬剔除 **30 天**（TTL），无报错的静默失效 |
| D2 | `LIMIT` 也发 `MONITOR` | `MONITOR` 是召回/搜索**硬剔除**，把最轻的非 PASS 档处置成与 REVIEW 等同的整帖隔离；而真正的排序降权只有作者级 `healthScaleOf` |
| D3 | 只接了旧 `upload` 与 `updateCaption` | 前端主路径 `presign → complete → MediaUploadFinalizer` 完全不发信号，方案实际覆盖不到主链路 |
| D4 | ADR-0013 / 0082 事实错误 | 「引擎另仓」「MONITOR 即降权」「构造器 23 参」三处口径不准 |

## 处置契约（跨服务，改动需同步网关 / 引擎 / 搜索）

| 层 | INTERCEPT | MONITOR | **DEMOTE（新增）** |
| --- | --- | --- | --- |
| 推荐召回层 `blockedContentKeys` | 剔除 | 剔除 | 不剔除 |
| 搜索 `visibleByContent` | 剔除 | 剔除 | 不剔除 |
| 播放前复校 `interceptedContentKeys` | 打断 | 不打断 | 不打断 |
| 精排 `RecallSupport#scoreOf` | — | — | 乘 `contentDemoteScale=0.7` |

裁定映射：`REVIEW → MONITOR/MID`（先发后审公域隔离）；`LIMIT → DEMOTE/LOW`（仍可进公域、可搜、可播，仅精排靠后）。

## 改动

### 网关侧（生产者）

| 文件 | 改动 |
| --- | --- |
| `service/review/Disposition.java` | **新增 `DEMOTE` 枚举值** + 四处消费契约表；明确区分内容级 DEMOTE 与作者级 `tier=DEMOTE` |
| `service/signal/ModerationSignalService.java` | 新增 `clearMediaDisposition(postId)`（整键删，人审用）与 `clearDemotionIfPresent(postId)`（**仅 DEMOTE 才删**，改描述用），均 fail-open |
| `service/signal/ModerationDownstreamRouter.java` | `LIMIT → DEMOTE` / `REVIEW → MONITOR`；新增 `routeCaptionUpdate(postId, verdict)`——改描述**只升级不降级**，PASS 只条件解除 DEMOTE，现存 MONITOR/INTERCEPT 一律保留 |
| `service/MediaUploadService.java` | L292 等过时注释修正；`updateCaption` 改走 `routeCaptionUpdate`；**`complete()` 补直传主路径接线**（`check` 纯检测重算裁定 + try/catch fail-open，`claim` 保证幂等） |
| `service/review/MediaReviewService.java` | `decideReviewTask` 判无违规且任务为 `CONTENT_REVIEW` 时，经 `disposition.clearContentSignal(mediaId, authorId)` 解除 MONITOR |
| `service/review/ViolationDispositionService.java` | 新增 `clearContentSignal`（按 mediaId 反查 postId）；`applyDisposition` 加 **DEMOTE 防御分支**：只写信号，不扣健康分/不摘时间线/不落处罚（防配置矩阵误配导致误下架） |
| `service/moderation/ContentSecurityProperties.java` | 4 个配置键的注释勘误（MONITOR 是硬剔除、DEMOTE 是轻降权） |
| `service/review/DispositionPolicy.java` | 注释说明矩阵可显式配 DEMOTE、默认矩阵不产出 |
| `service/search/SearchService.java` | **零逻辑改动**，补注释确认 DEMOTE 天然可见、刻意不加分支 |

**零构造器变更**：`MediaReviewService`(19 参) / `ViolationDispositionService`(11 参) / `MediaUploadService`(23 参) 都被单测手动 `new`，全部通过已有依赖接线，避免重演 0468950 那类 CI 编译断点。

### 引擎侧（消费者）

| 文件 | 改动 |
| --- | --- |
| `ranking/FeedModerationProperties.java` | 新增 `enable-content-demotion=true` / `content-demote-scale=0.7`（带默认值，**不改 yml**） |
| `client/ReviewSignalClient.java` | 新增 `contentDemotionScaleOf(postId)`（懒读、fail-open）+ `contentDemotion` 维度指标；`blockedContentKeys`/`interceptedContentKeys` 明确不含 DEMOTE |
| `timeline/RecallContext.java` | 新增 `contentDemotionCache`（按 timelineKey 本页缓存） |
| `timeline/RecallSupport.java` | `scoreOf` 计算 `healthScale × contentScale` 作为审核综合降权系数传入精排 |
| `ranking/RankingFeatures.java` | Javadoc 说明 `authorHealthScale` 字段语义扩展（字段名保留，避免改模型契约与既有测试） |
| `timeline/RecommendationPipeline.java` / `ranking/FeedFilterContext.java` | **编排零改动**（仅注释）；降权是排序输入，不是过滤输入 |

**对原计划的修正**：原打算「批量算 `demotedPosts` 塞 `FeedFilterContext`」。读码发现 `scoreOf` 在召回通道内部 `sortByScore` 也被调用，且早于 `FeedFilterContext` 构造（pipeline 第 348 行 vs 第 385 行），批量预取必漏通道内排序。改为与作者级 `healthScaleOf` 完全对称的懒读 + 本页缓存，覆盖全部打分点。

### 文档

| 文件 | 改动 |
| --- | --- |
| `docs/adr/0014-content-demote-and-signal-lifecycle.md` | **新增**：背景(D1–D4) / 决策 / 后果含负面成本与灰度发布顺序 / 备选 / 落地记录 / 未做边界 |
| `docs/adr/0013-content-review-limit-downstream.md` | 头部标注「被 ADR-0014 取代」；**追加** §6 偏差记录（不改历史结论） |
| `docs/adr/README.md` | 0013 状态改为「被 0014 取代」，索引追加 0014 |
| `docs/changelog/0083-*.md` | 本文件；0082 历史快照不动 |

## 验证

| 验证项 | 方法 | 结果 |
| --- | --- | --- |
| 双模块编译 | `mvn -pl tf-gateway,tf-feed-engine -am -Pci compile` | 通过 |
| 网关全量回归 | `mvn -pl tf-gateway -am -Pci test` | **302 通过 / 0 失败 / 8 跳过**（含 `ArchitectureTest` 5/5） |
| 引擎全量回归 | `mvn -pl tf-feed-engine -am -Pci test` | **222 通过 / 0 失败 / 0 跳过**（含 `ArchitectureTest` 3/3） |
| 分档回归锁 | `ModerationDownstreamRouterTest` 18 例 | LIMIT **绝不发 MONITOR**；改描述不降级；PASS 只条件解除 |
| 解除闭环 | `ModerationSignalServiceTest` 19 例 | 整键删 / 仅 DEMOTE 才删 / MONITOR·INTERCEPT 不删 / 异常 fail-open |
| DEMOTE 防御分支 | `ViolationDispositionServiceTest` 5 例 | 不扣分、不摘时间线、不下架、不落处罚 |
| 消费契约 | `ReviewSignalClientTest` 13 例 | DEMOTE 不进召回拦截、不打断播放、系数 0.7 |
| 精排相乘生效 | **新增** `RecallSupportContentDemotionTest` 5 例 | `0.5 × 0.7 = 0.35`；开关关零 Redis 读；本页缓存每帖只打一次 |

## 发布与回滚

- **发布顺序**：网关先、引擎后。灰度期旧引擎不认识 DEMOTE → 退化为「不降权但可见」（fail-safe 方向）；反之上引擎先则 DEMOTE 永不出现。无需数据迁移。
- **回滚**：`turbofeed.content-security.limit-publish-signal=false` 回到「LIMIT 无下游动作」；
  `turbofeed.feed.moderation.enable-content-demotion=false` 关掉引擎降权且消除额外 Redis 读；两者独立。

## 明确未做（诚实边界）

- **未真启动联调**：本轮是编译 + 全量单测门禁，未在本地起网关/引擎/Redis 集群做端到端运行时验证。
- **举报处置链路**：`softLimit` 写 `tf:media:disposition:{postId}=MONITOR` 并摘时间线；人审判无违规只清了 `tf:mod:media`、**未重投时间线**。症状是「搜索恢复可见、发现流仍缺该帖」，属 P0-a 举报处置域，另立修复项。
- **申诉翻案** `handleAppeal(upheld=true)` 未清 INTERCEPT 信号，同上另立修复项。
- `clearDemotionIfPresent` 读 + 删非原子（极端并发可能误删一次），低频低危，未做 Lua 原子化。
- `0.7` / `LOW` / `MID` 仍是工程默认值，待运营按效果数据校准。
