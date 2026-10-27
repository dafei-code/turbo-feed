# ADR-0008：公域时间线的分层边界与「格式单一真值」

- **状态**：已采纳（Accepted, 2026-10-27）
- **所属改进项**：`improvement-plan.md` §7.7 / P1-1
- **决策人**：dafei
- **关联 ADR**：[0001 测试策略](0001-testing-strategy.md)（本批新增的格式契约测试）、
  [0005 契约版本协商](0005-contract-versioning.md)（同样是「解析入口唯一化」问题）

## 1. 背景（Context）

公域时间线读模型有两类资产、三个生命周期：

| 资产 | 形态 | 写入 | 搬移 | 读取 |
|---|---|---|---|---|
| 池桶 | ZSET `tf:feed:tl:{pool}:{yyyyMMdd}` | `append` | `promote` / `demote` | `readPage` → `RecommendationPipeline` |
| 反查索引 | String `tf:feed:idx:{timelineKey}` = `{bucketKey}\u0001{member}` | `append` | 同上（必须同步改写） | `removeStrict` / `currentPool` / `inflowScoreOf` / 热点召回还原 |

两者都不是「一个有类型的数据结构」，而是**靠字符串约定维持的格式**。问题正出在这里：

1. 桶键格式在 **7 处**各拼一遍；反查索引值的拼装/解析在 **6 处**各写一遍。
2. 这 6 处里两组实现的边界判断**恰好相反**（`FeedTimelineStore` 用 `sep <= 0`，
   `RecommendationPipeline#memberOf` 用 `sep < 0`），对「索引值以 SOH 开头」这种脏数据给出相反结论。
3. `FeedTimelineStore` 自己又声明了一份 `IDX_SEP`，把 `TimelineSupport.IDX_SEP` 的静态导入
   **静默遮蔽成死代码**——两份真值，其中一份永远不生效。
4. `promote` / `demote` 是两份 38 行逐行镜像（连注释都对称），方向只差一个比较符。

**约束**（决定了「不能靠自觉」）：

- 写路径有 fail-open 与严格两条入口，异常口径不同，容易只在其中一条改对；
- 索引与 ZSET 之间没有事务（先 GET 索引、再 ZSCORE、再 ZREM/ZADD），一致性必须在代码里显式处理；
- 格式分叉的症状**不是异常**，而是「某些内容下架不掉 / 晋级不生效」——
  这类静默失效是本项目历史上反复出现的故障形态。

## 2. 决策（Decision）

1. **分层固定为三段，禁止越界**：
   - `FeedTimelineStore`：池 ZSET 与反查索引的**存储读写 + 游标**（读模型存储层）；
   - `RecommendationPipeline`：**策略编排**（召回配比、粗排、精排、重排）；
   - `TimelineSupport`：**格式与共享纯函数的唯一真值**（无 Spring、无 I/O、包可见）。
   判据：Store 不知道召回配比与排序策略；Pipeline 不读时间线桶；两者共用的一切都进 TimelineSupport。
2. **两个 Redis 格式只允许在 `TimelineSupport` 拼装与解析**：
   `bucketKey` / `dateOf` / `indexKey` / `indexValue` / `parseIndex` / `poolOfBucket` / `dateOfBucket`。
   其他类**不得出现** `tf:feed:tl:`、`tf:feed:idx:` 字面量或 SOH 分隔符。
3. **非法格式一律「不猜」**：前缀不符、字段缺失、池号非数字 → `-1` / `null`，调用方放弃该动作。
   绝不从任意字符串里硬抠一个数字——抠出来的池号会指向一个**真实存在**的桶，
   于是「挪错桶」被当成成功写下去。
4. **搬池只有一个实现**：`relevel(key, targetPool, Direction)`，两个 public 方法只做转发。
   方向是入参，不变量（score 不变 / 索引必须同步改写 / 索引与 ZSET 不一致就放弃）集中一处。
5. **索引存在但格式非法 ≡ 未命中**：必须让调用方退回尽力扫描，而不是当成「已处理」。
6. **不按 `improvement-plan` §3 的原分法继续拆类**（理由见 §4 备选方案）。

## 3. 后果（Consequences）

**正面**

- 6 处解析 + 7 处拼装收敛到一处，格式改动一次全局生效；
- 顺带修掉一个真 bug：索引**存在但格式非法**时 `detach` 原先返回 `true`（只删索引、不摘桶），
  `removeStrict` 因此不再退回扫描——内容还在桶里，表现为「下架成功但内容仍可见」；
- 镜像实现消失后，方向性 bug（改一边漏一边）在结构上不可能再发生；
- 格式契约有了独立单测（`TimelineSupportTest` 10 例），可以钉住畸形输入而不是只测 happy path。

**负面 / 成本**（必须正视）

- **可读性问题只缓解、未解决**：`FeedTimelineStore` 仍是 507 行、仍同时承担「取候选 / 搬池 / 定位」
  三件事，没有变成 §3 设想的那 5 个类。本 ADR 明确不为「行数」而拆。
- `TimelineSupport` 从 140 行涨到 269 行（大半是 javadoc）。它仍是**包可见**——
  若日后网关侧回填工具或搜索引擎也要解析这套格式，得再决定是提为公共工具还是各留一份。
- **行为比原先更严格**：桶键前缀必须匹配才算合法。历史脏数据从「可能碰巧成功」变为「放弃动作」；
  `removeStrict` 会因此退回扫描（结果正确、但单次开销变成最多 3 天 × 3 池的扫描）。
- `demote(targetPool == MAX_POOL_LEVELS)` 原先在入口就拒绝、不读索引；现在由方向判断覆盖，
  结论一致但多一次 O(1) `GET`。
- 收敛带来一个**集中风险点**：`parseIndex` 一处写错会影响全部 6 个调用方。
  用「格式契约测试 + 畸形输入用例」对冲，而不是靠分散实现互相兜底。

## 4. 备选方案

| 方案 | 否决理由 |
|---|---|
| 按 §3 原分法拆成 `TimelineZSetStore` / `FeedRanker` / `ColdStartService` / `PoolTransitionService` / `ModerationGate` | 打分、槽位、多样性重排、冷启动、审核键收集**已经**迁入 `RecommendationPipeline`；剩下的 507 行基本是同一份 Redis 布局的 CRUD。再切 5 个类会让一次 `append` 跨 3 个类，可读性收益小于调用链成本 |
| 新建 `TimelineIndex` 类专门管反查索引格式 | 与 `TimelineSupport`（已自我声明为「共享常量与纯函数的唯一真值」）职责重叠；两个都放格式=重新长出两份真值，正是本 ADR 要消灭的形态 |
| 保持现状，仅在 6 处加注释「改这里要同步改那里」 | 分叉已是事实（两组判断相反），注释挡不住下一次复制；注释也无法被 CI 检查 |
| 只给格式加单测、不收敛实现 | 测试只能钉住被测试的那一份，另一份继续分叉，且两份都"有测试覆盖"的假象更危险 |

## 5. 落地记录

**已改文件**

- `TimelineSupport`：新增 `PER_BUCKET_CAP` / `BUCKET_TTL` / `IndexEntry` /
  `bucketKey` / `dateOf(Instant|LocalDate)` / `indexKey` / `indexValue` / `parseIndex` /
  `poolOfBucket` / `dateOfBucket`（+ 私有 `restOfBucket`）。
- `FeedTimelineStore`：删除本类 `IDX_SEP` / `PER_BUCKET_CAP` / `BUCKET_TTL` / `bucketOf`；
  `promote`/`demote` 合并为 `relevel`；`currentPool` / `inflowScoreOf` / `detach` 改用 `parseIndex`；
  `detach` 对非法索引改返回 `false`；清理 13 个死 import + 7 个死静态导入。
- `RecommendationPipeline`：`memberOf` 与冷启动收集改用 `parseIndex` / `bucketKey` / `dateOf`。
- `PlayabilityService`：`authorIdFromKey` 由自抄一份改为委托 `TimelineSupport`。

**已验证**

- `mvn -o test` 全仓 **416 例**全绿（tf-feed-engine 141）。
- 新增 `TimelineSupportTest` 10 例（往返、空桶 key、无分隔符、外来前缀、非数字池号、缺日期）；
  `FeedTimelineStoreTest` +7 例（demote 正常路径 / 反向拒绝 / 非法桶键放弃 / score 缺失放弃 /
  `currentPool` 正反两例 / 索引非法时退回扫描）。

**待验证 / 未做**

- `readPage` 中 `poolReadWeightEnabled=false` 的兜底合并分支未拆出：它绑定的是「按池权重改造前」的
  老语义，硬拆会让回滚路径变复杂，属独立议题。
- 本批全部为单测验证，未做真启动 e2e（改动不触达 HTTP 契约，`mvn test` 的引擎用例已覆盖读写路径）；
  下次有 Redis 环境时，随「搬池 e2e」一起补一次真链路。
