# ADR-0015：审核信号解除闭环补齐、条件解除原子化与推荐流读取侧重签

- **状态**：已采纳（Accepted，2026-11-08）
- **所属改进项**：内容安全子系统 / 举报处置域 / 展示侧 URL 生命周期（承接 ADR-0014 §5「明确未做」）
- **决策人**：dafei
- **关联 ADR**：[0014](0014-content-demote-and-signal-lifecycle.md)（内容级 DEMOTE 与解除闭环）、[0013](0013-content-review-limit-downstream.md)（已被取代）、[0008](0008-timeline-layering.md)（公域时间线边界）、[0005](0005-contract-versioning.md)（跨服务契约变更纪律）
- **关联 changelog**：[0084](../changelog/0084-举报处置恢复闭环与读取侧重签.md)

> 本 ADR **不改 ADR-0014 的结论**，只补齐其 §5 登记的遗留项，并记录实现过程中发现的两个新风险。
> 处置档位（INTERCEPT / MONITOR / DEMOTE）与跨服务消费契约仍以 ADR-0014 §2.1 为准。

## 1. 背景（Context）

ADR-0014 落地（提交 `460ed15`）时，「诚实边界」里列了 5 项未做。本轮逐项核对后，确认其中 3 项是**真实线上缺陷**，另 1 项是竞态，1 项需要运营数据而非代码；另有前序遗留的展示侧 403 缺口：

| 编号 | 问题 | 实际症状 |
| --- | --- | --- |
| G1 | `softLimit` 摘公域时间线后，人审判无违规只清 `tf:mod:media`、**不重投时间线** | 搜索恢复可见、发现流仍缺该帖；`tf:media:disposition` 标记永久残留 |
| G2 | `handleAppeal(upheld=true)` 只把状态翻回 `APPROVED` 并重投，**不清 INTERCEPT 信号** | 申诉赢了，引擎召回层/搜索层仍按「已下架」硬剔除，最长 30 天（KV TTL） |
| G3 | `clearDemotionIfPresent` 是 Java 侧 `HGET` 判断后再 `DEL` 两步 | 并发下若另一路在两步间把 DEMOTE 升级为 MONITOR/INTERCEPT，改一次描述即可抹掉人审隔离或下架结论 |
| G4 | 公域推荐流读取路径未现签 | 引擎时间线物化的是**投递时刻**的预签名 URL，私有桶下 900 秒过期，`FeedItemMapper.toView` 原样透传 → 整页 403 |
| G5 | `contentDemoteScale=0.7` / `defaultLimitSeverity=LOW` / `defaultReviewSeverity=MID` | 工程默认值，无运营校准口径、无灰度与回滚线 |

实现过程中另发现两个**必须收紧**的风险（否则修复本身会引入回归）：

- **R1 — 清除范围扩大**：把 `decideReviewTask(takedown=false)` 无违规分支统一改成「恢复公域」后，举报累计 / 热度 / 晋级复扫等任务判无违规也会清 `tf:mod:media`。若该帖同时还有**尚未判决**的 CONTENT_REVIEW 隔离，就会提前放行未审内容、甚至误清 INTERCEPT。ADR-0014 明确规定「只有 CONTENT_REVIEW 才清信号」。
- **R2 — 标记删除顺序**：若「删 `tf:media:disposition` 标记」发生在「重投时间线」之前，而投递失败或 DB 事务回滚，**Redis 删除不随 DB 事务回滚**，内容会永久停在「已摘除 + 无标记」的中间态，补偿任务无从识别。

## 2. 决策（Decision）

### 2.1 判无违规恢复公域：`restoreAfterNoViolation(mediaId, authorId, taskType)`

放在 `ViolationDispositionService`（门面 `MediaReviewService` 已被多个单测手动 `new`，保持零构造器变更）。三道闸门 + 严格顺序：

| 闸门 / 步骤 | 规则 | 目的 |
| --- | --- | --- |
| ① 状态闸门 | 仅 `MediaStatus.APPROVED` 继续；非 APPROVED **连信号都不清** | `TAKEN_DOWN` 的下架结论不能由一次「判无违规」推翻，保护 INTERCEPT |
| ② 任务类型闸门 | `taskType != CONTENT_REVIEW` 且无 softLimit 标记 → 直接返回、不碰任何 Redis 键 | 保留 ADR-0014 原语义，消除 R1 |
| ③ 标记闸门 | 只有 `tf:media:disposition:{key}=MONITOR`（确实被 `softLimit` 摘过）才重投 | 幂等：无标记的内容审核任务只清信号、不重复 append |
| ④ 顺序 | 清信号 → 构造整帖视图 → `publishAppend` → **最后**删标记 | 消除 R2：投递/事务失败时标记保留，OutboxRelay 或下次判定可重试 |

`key` 与 `softLimit` 写入口径完全一致：有 `postId` 用 `postId`，历史单图帖（`post_id=''`）回退 `mediaId`。整帖视图由 `delivery.asTimelinePost` 补齐多图，池层级取 `accountCreditService.ensure(authorId).poolLevel()`，与审核通过/申诉翻案同一套路。全程 fail-open：异常只记日志并返回 `false`，不影响人审结论落库。

### 2.2 申诉翻案：清信号必须先于重投

`handleAppeal(upheld=true)` 在 CAS（`APPEALING → APPROVED`）成功的分支内、重投之前调用 `disposition.clearContentSignal(mediaId, authorId)`。发件箱是异步投递，先清可避免「投递已落地、引擎却仍读到旧 INTERCEPT」。CAS 落空时不重复清、不重复重投，保持原有幂等性。

### 2.3 条件解除改为单键 Lua 原子脚本

`ModerationSignalService.clearDemotionIfPresent` 改为一次 `EVAL`：

```lua
local action = redis.call('HGET', KEYS[1], 'action')
if action == ARGV[1] then return redis.call('DEL', KEYS[1]) end
return 0
```

- 目标值 `Disposition.DEMOTE.name()` 由 `ARGV[1]` 传入，避免脚本内硬编码漂移；
- 只访问 `KEYS[1]` 一个键，满足 Redis Cluster 单 slot 约束；Spring Data Redis 自动处理 EVALSHA 的 NOSCRIPT 回退；
- 返回 `1` = 已删除、`0` = 未删（action 缺失或不是 DEMOTE）；Java 侧不再有 `opsForHash().get` / `delete` 两次往返；
- 继续 fail-open，异常只记日志。

**判定权完全在 Redis 侧**：改描述 PASS 永远只能解除轻降权，MONITOR / INTERCEPT 仍只能由各自业务流程解除。

### 2.4 推荐流读取侧按 URL 反推对象名重签

约束：`FeedItemView.imageUrls()` 是 **URL 列表而非 mediaId 列表**，契约在 tf-shared，改字段会牵动引擎与历史 Redis 成员串；逐帖回查 DB 拿 mediaIds 又会给推荐流加 N 次分片查询。

因此选择**零契约变更、零 DB 查询**的读取侧方案：

1. `MediaUrlResolver.resolveFromUrl(staleUrl)`：去 query、去 scheme+host，`lastIndexOf("/media/")` 跳过 bucket 段，校验第二段为数字 userId，得到 `media/{userId}/{uuid}.{ext}`，再复用 `resolve(mediaId)` 现签。非 minio 存储或反推失败**原样返回**，历史公网直链不被改坏。
2. `FeedItemMapper.toView(item, UnaryOperator<String> rewriter)` 新增重载，逐条重写 `url` 与 `imageUrls`；映射层保持纯静态、不依赖 Spring Bean；无参 `toView(item)` 保留给离线核对等场景。
3. `FeedEngineClient.recommended(...)` 传 `urlResolver::resolveFromUrl`。构造器新增 1 个依赖——已核对全仓：只有 Spring 注入点，测试中仅 `@Mock`，无手动 `new`，不会重演 `0468950` 的 CI 编译断点。

### 2.5 运营校准口径与发布清单（**规划中未实施**）

本节的数值校准与灰度执行属于运营动作，**代码无法代替**；以下为约定口径与 checklist，不代表已经调过参或已经灰度。

**依赖指标（按帖/按作者分组，观察窗口 ≥ 7 天）**

| 指标 | 用途 | 恶化方向 |
| --- | --- | --- |
| LIMIT 内容的曝光占比、CTR、完播率 | 定 `contentDemoteScale`（现 0.7） | 过度降权 → 曝光塌缩、作者投诉 |
| REVIEW 转人审的通过率 / 平均审核时延 | 定 `defaultReviewSeverity`（现 MID）与 MONITOR 隔离时长 | 通过率极高 → 隔离过重、审核队列积压 |
| 改描述 PASS 后 24h 内复发命中率 | 检验「只清 DEMOTE、保留 MONITOR」是否够严 | 复发率高 → 需收紧解除条件 |
| 举报判无违规后的申诉/二次举报率、申诉翻案率 | 校验 G1/G2 恢复闭环是否误放 | 翻案后违规复现 → 恢复闸门过松 |
| KV 信号残留数（`tf:mod:media` TTL 内 action 分布） | 校验解除闭环覆盖率 | 残留高 → 有解除路径漏接 |

**调参顺序**：先单集群灰度 5% → 观察 24h → 按档位单独调 1 个参数（一次只动一个）→ 再观察 24h → 扩量。**回滚线**：任一核心指标偏离基线 > 15%，或出现「改描述绕过隔离 / 申诉翻案后违规复现」，立即把对应开关置回（`enable-content-demotion=false` 或回调系数到 1.0），不回滚代码。

**配置键（均可热更或重启生效，无需数据迁移）**

| 键 | 现值 | 作用 |
| --- | --- | --- |
| `turbofeed.feed.moderation.enable-content-demotion` | `true` | 引擎内容级降权总开关 |
| `turbofeed.feed.moderation.content-demote-scale` | `0.7` | DEMOTE 精排系数 |
| `turbofeed.content-security.default-limit-severity` | `LOW` | LIMIT 档 severity |
| `turbofeed.content-security.default-review-severity` | `MID` | REVIEW 档 severity |
| `turbofeed.media.signal.media-ttl-seconds` | `2592000` | 信号残留窗口（30d） |

**发布顺序：网关先、引擎后**

1. 本轮改动**不涉及 `tf:mod:media.action` 的新枚举值**，跨服务契约与 `460ed15` 完全一致，无需数据迁移；
2. 先发网关（`460ed15` 已上，本轮为网关侧修复 + 读取侧重签），旧引擎不认新逻辑也不受影响；
3. 再发引擎（本轮引擎零改动，仅确认 `460ed15` 的 DEMOTE 消费已全量）；
4. 灰度期旧引擎若未升级，DEMOTE 会退化为「**不降权但可见**」——方向安全（宁可少降权，不可误剔除），无需回滚；
5. 观察项：推荐流图片 403 率（应为 0）、`tf:mod:media` 残留数、判无违规后时间线回补成功率、申诉翻案后搜索可见率。

## 3. 后果（Consequences）

**正面**

- G1/G2 两处「结论已推翻但内容仍不可见」的静默失效闭环，且恢复动作幂等、可重试；
- 改描述 PASS 不再存在「抹掉 MONITOR/INTERCEPT」的并发窗口；
- 推荐流不再依赖投递时刻的短时凭据，私有桶下过期 403 从根上消除；
- 全程零跨服务契约变更、零数据迁移，发布顺序宽松。

**负面 / 成本（必须保留）**

- **URL 反推是约定式解析**：依赖对象名格式 `media/{userId}/{uuid}.{ext}` 与 URL 路径形态。若将来改 keyPrefix、启用 CDN 重写路径或 URL 编码，`extractMediaId` 会失败并静默回退到旧 URL（重新 403）。必须同步维护该方法与其单测，且最好在签名异常时加可观测指标。
- **Lua 脚本的真实语义无法在纯 Mockito 单测里验证**：当前单测只能断言「调用了一次 EVAL、脚本含 HGET/DEL/单键、Java 侧不再两步往返」，「非 DEMOTE 不删」由脚本返回 0 模拟。真实 Redis 上的脚本行为仍待真启动验证（见 §5）。
- **Redis 标记删除不随 DB 事务回滚**：已用「最后删」把不一致方向收敛为「标记残留、可重试」，但极端情况下仍可能出现「时间线已回补、标记还在」，需要一次额外判定才会清理（幂等，不会二次 append）。
- **`FeedEngineClient` 构造参数 +1**：虽然当前无手动 `new`，未来新增 Spring 上下文之外的构造点必须同步；这是零构造器变更原则的一次受控例外。
- **恢复逻辑多了一道「任务类型」耦合**：`restoreAfterNoViolation` 的语义依赖调用方透传正确 taskType，传错会导致该恢复的不恢复（保守方向，不会误放）。

## 4. 备选方案

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| A：判无违规无条件清信号 + 重投 | **否决** | 会误清 INTERCEPT、把已下架内容灌回公域，并把未审内容提前放行（R1） |
| B：给 `FeedItemView` 增加 `mediaIds` 字段 | **否决** | 跨服务契约变更 + 历史 Redis 成员串迁移，成本远高于收益 |
| C：读取侧按 postId 回查 DB 拿逐图 mediaId | **否决** | 推荐流一页 N 帖 = N 次分片查询，热路径不可接受 |
| D：`WATCH/MULTI/EXEC` 乐观锁替代 Lua | **否决** | Redis Cluster 下 `SessionCallback` 事务语义更复杂，单键 CAS 用 EVAL 更直接 |
| E：私有桶改公读，彻底不签名 | **否决** | 安全默认不可退让（P0-3 私有化结论） |
| F：只补 G1/G2，不动竞态与读取侧 | **否决** | G3 是「改文案绕过隔离」的安全窗口，G4 是整页 403，同属本轮「继续完成」范围 |

## 5. 落地记录（2026-11-08）

**网关侧改动**

| 文件 | 改动 |
| --- | --- |
| `ViolationDispositionService` | `clearContentSignal` 改为两类推翻结论共用；新增 `restoreAfterNoViolation(mediaId, authorId, taskType)`（三闸门 + 最后删标记） |
| `MediaReviewService` | `decideReviewTask(takedown=false)` 统一委托恢复并透传 `task.taskType()`；`handleAppeal(upheld=true)` CAS 成功分支在重投前清 INTERCEPT |
| `ModerationSignalService` | `clearDemotionIfPresent` 改单键 Lua `CLEAR_DEMOTE_SCRIPT`（HGET + 条件 DEL），保留 fail-open |
| `MediaUrlResolver` | 新增 `resolveFromUrl` / `extractMediaId`，按对象名重签；反推失败或非 minio 原样返回 |
| `FeedItemMapper` | 新增 `toView(item, UnaryOperator<String> rewriter)` 重载，逐 URL 重写；无参重载保留 |
| `FeedEngineClient` | 注入 `MediaUrlResolver`，推荐流映射传 `urlResolver::resolveFromUrl` |

**测试**

- `ViolationDispositionServiceTest` 5 → 11 例：TAKEN_DOWN 不碰信号、CONTENT_REVIEW 只清不重投、其它任务类型不碰信号、softLimit 重投且**投递先于删标记**、帖子视图缺失保留标记、blank postId 用 mediaId 作标记键。
- `MediaReviewServiceTest` 4 → 7 例：判无违规透传 taskType、翻案清信号先于重投、CAS 落空不清不投。
- `ModerationSignalServiceTest` 19 → 20 例：断言只调一次 EVAL、脚本含 HGET/`'DEL'`/`ARGV[1]`、不含 `KEYS[2]`、Java 侧不再 `opsForHash`/`delete`；另覆盖脚本返回 0、blank、Redis 异常。
- **新增** `MediaUrlResolverTest` 6 例（预签名/公网/bucket 名含 media 的反推、非法路径拒绝、minio 重签、非 minio 与未知路径回退）、`FeedItemMapperTest` 5 例（封面与多图全重写、无参重载原样、历史单图回退、未知状态降级、null 安全）。
- 定向门禁：`49 通过 / 0 失败 / 0 跳过`；双模块全量门禁：**网关 323 / 0 / 8（42 类，含 `ArchitectureTest` 5/5）、引擎 222 / 0 / 0（33 类，含 `ArchitectureTest` 3/3），`BUILD SUCCESS`**。引擎本轮零代码改动。

**发布 / 回滚**

- 发布顺序：网关先、引擎后；本轮引擎零改动，契约与 `460ed15` 一致，无数据迁移；详见 §2.5 checklist。
- 回滚：读取侧重签出问题可先把 `storage` 切回非 minio 原样透传路径，或直接回滚网关；Lua 脚本回滚为两步读删虽恢复竞态但功能等价，属可接受降级。

**明确未做（诚实边界）**

- **未真启动端到端联调**：本机仅 MySQL `3306` 在线，Redis 集群 `7000-7005`、MinIO `9000`、Docker daemon 均未运行。以下运行时验证仍待环境就绪后补：Lua 脚本在真实 Redis 上「非 DEMOTE 不删」、判无违规后时间线真实回补、申诉翻案后搜索/召回恢复、推荐流预签名 URL 实际可访问。
- **MinIO 测试 PNG 孤儿对象未清理**：依赖 MinIO 容器，环境未就绪；需先只读盘点再小批量删除。
- **运营校准未执行**：`0.7 / LOW / MID` 仍是工程默认值；§2.5 只给了口径与流程，真实数值需运营数据。
- **未新增指标埋点**：URL 反推失败目前仅静默回退，无可观测计数；建议后续补 `media.url.resign_fallback` 计数器。
