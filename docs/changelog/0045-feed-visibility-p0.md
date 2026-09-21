# 0045 · 发现流可见性 P0 止血（Redis 配置下沉 / 存量补投 / 缓存失效 / 双 Tab / 投递可观测）

> 触发：2026-09-21 线上「feed 页看不到自己发的、也看不到别人发的」。
> 本文档记录**根因取证**与**五项止血改动**。抖音式推荐机制（流量池赛马 / 推拉结合 / 召回排序）不在本期，见
> `docs/architecture/recommendation-design.md`（规划中）。

## 0. 结论先行

页面空白不是"推荐算法没做"，是**四个故障叠加**，而它们共同的表现都是"空列表"：

| # | 根因 | 证据 |
|---|---|---|
| 1 | feed-engine 从未启动（:8083 无进程） | 网关 `FeedEngineClient` 返回 empty → `degraded-mode=empty` → `[]` |
| 2 | **0044 漏改**：feed-engine 仍连已下线的单机 6379 | 其 `application.yml` 只有 `host/port`，没有 `turbofeed.redis.*` |
| 3 | 时间线是**写时物化**，存量 APPROVED 从未投递，且无自愈通道 | 库里 6 条 APPROVED，Redis 6 节点上 `tf:feed:*` **零 key** |
| 4 | 推荐流 15s 缓存只 SET 不 DEL | 补投后立刻读仍 `[]`，等 17s 才读到 |

另有第 5 个**产品层**问题：抖音里"自己发的"在**我的作品**，不在推荐流。`/api/media/mine` 本来就可用
（按 user_id 分片查库、不依赖引擎），只是前端没调，且 `author` 硬编码 `'我'`。

**关键认知**：公域可见性是写时物化的，库里有 APPROVED ≠ 发现流里有。这条链路断裂后**不会自愈**。

## 1. P0-1 Redis 配置下沉（新模块 `tf-redis`）

- `RedisTopologyConfig` 从 `tf-gateway/config/` 移到新模块 **`tf-redis`**（包 `com.turbofeed.redis`）。
- **为什么是新模块而不是塞进 `tf-shared`**：`tf-shared` 的定位是「零三方依赖」的契约库，放进 Spring Redis 装配会破坏它。
- **为什么用 `@AutoConfiguration` + `AutoConfiguration.imports` 而不是普通 `@Configuration`**：
  库模块在引用方的组件扫描路径之外，普通 `@Configuration` 扫不到，要求每个服务 `@Import` 是"迟早会漏"的隐性契约。
  改为自动装配后**引入依赖即生效**。
- **顺序 `before = RedisAutoConfiguration`**：必须先注册本工厂，官方自动装配的
  `@ConditionalOnMissingBean(RedisConnectionFactory)` 才会退让；否则出现两个工厂 → 注入歧义。
- 两边 pom 引入 `tf-redis`；feed-engine 的 `application.yml` 补上同名同义的 `turbofeed.redis.*`。
- 本机新增 `tf-feed-engine/application.yml`（gitignore 已加该路径）。

> ⚠️ 排障坑：删了 `.java` 但 `target/classes/*.class` 残留 → 启动时两个同名 Bean 冲突
> `BeanDefinitionOverrideException`。**删 class 要精确到文件**，不要 `touch` 全量源码触发重编译。

## 2. P0-2 存量补投（`FeedBackfillService` + 管理接口）

- 新增 `FeedBackfillService`：分页扫 `listApprovedGlobal` → 按帖投递到引擎。
- 管理接口 `POST /api/admin/feed/timeline/backfill`（`SYSTEM_CONFIG` 权限），支持 `batchSize` / `maxPosts`。
- **L0 不入池**：`CreditLevel.L0`（新人观察期/违规加严）`poolLevel()=0`，补投显式跳过。
  引擎侧 `appendStrict` 会把 0 兜底成 1，所以这道闸门必须在调用方把住。
- **幂等**：引擎 `append` 先摘旧位置再写新，可重复执行。
- **代价**：`listApprovedGlobal` 是跨分片广播。补投是运维动作不是在线接口，必须限 `maxPosts` 并避开高峰。

## 3. P0-3 推荐流缓存主动失效

- `RecommendedFeedService#invalidate()`：append / remove 后精确 `DEL`「前 5 页 × {20,50}」共 10 个确定 key。
- **为什么不用 SCAN**：按 `tf:feed:rec:*` 通配扫描会长时间占用 Redis 单线程，把一次发布放大成全局抖动。
- **⚠️ 为什么逐个 DEL 而不是批量 DEL**：Redis Cluster 下多 key 命令要求同槽，否则 `CROSSSLOT`。
  这些 key 无 hashtag、天然散落不同槽，批量删必报错。
- 15s TTL 保留作兜底（覆盖配置外的页码/页大小组合）。

## 4. P0-4 feed 页「推荐 / 我的」双 Tab

- 侧边栏 `推荐` / `我的` 加 `data-tab`，`switchTab()` 切换并重新拉数据。
- `我的` → `/api/media/mine`，`推荐` → `/api/feed/recommended`。
- 去掉 `author` 硬编码 `'我'`：从 mediaId（`media/{userId}/{uuid}.ext`）解析 uid 尾 4 位。
- `我的` 页展示审核状态（PENDING/APPROVED/REJECTED…），一眼看出"这条为什么没进公域"。
- 两 Tab 空态文案不同：推荐空提示「可能是引擎未启动或时间线未补投」，避免误判故障范围。
- 关注/朋友标 `data-tab="todo"`，点击提示 P2 规划中（需先建关注关系）。

## 5. P0-5 投递失败可观测 + 补投报告不再说谎

- 新增 `FeedDeliveryHealth`：append/remove/read 的成功与失败计数 + 最后失败时间与原因。
- 管理接口 `GET /api/admin/feed/timeline/delivery-health`（`DASHBOARD_VIEW`）。
- 失败日志直接给出补救指引：「引擎恢复后执行 `/api/admin/feed/timeline/backfill` 补投」。

### 5.1 顺带修掉的真 bug：补投把失败当成功

- **现象**：引擎全挂时跑补投，报告显示 `delivered=3, failed=0`，而健康度显示 `appendFailure=3`。
- **根因**：`FeedEngineClient#append` 是 fail-open，内部吞掉异常只记 WARN，返回 `void` →
  `FeedBackfillService` 无从判断，一律按成功计。
- **修法**：`FeedTimelinePublisher#append/remove` 与 `FeedEngineClient#append/remove` 改为**返回 boolean**。
  - HTTP 实现返回送达与否；MQ 实现本就 fail-fast（失败直接抛），未抛即 `true`。
  - 审核主流程忽略返回值（行为不变）；补投据实计入 delivered / failed。
- **修后实测**：引擎不在 → `delivered=0, failed=3`；引擎在 → `delivered=3, failed=0`。
- **教训**：一份把彻底失败包装成成功的报告，比没有报告更危险。

## 6. 验证（全部实机跑通）

| 场景 | 结果 |
|---|---|
| feed-engine 零环境变量从模块目录启动 | Started，连上集群，投递/读回正常 |
| 网关启动（删掉旧配置类后） | Started，0 ERROR，登录返回 token |
| `/api/media/mine` | 3 帖 APPROVED（不依赖引擎） |
| 清空 Redis → 补投前 | 0 条 |
| 补投（引擎在）→ 立刻读 | 3 条（**不等 15s**，P0-3 生效） |
| 补投（引擎不在） | `delivered=0 failed=3`，健康度 `appendFailure=3` |
| 集群下 invalidate | 无 CROSSSLOT |

## 7. 遗留 / 下一步

1. **推荐流仍无个性化**：只有一个全局时间倒序 ZSET，无召回/排序。抖音式能力见 P1（流量池赛马）→ P2（推拉结合）→ P3（召回排序）。
2. **`skipped` 的那 2 条**：uid 377051744192040960 是 L0 新人观察期，语义上不进公域——符合预期，但需要人审通过转正后才会入流。
3. **多实例计数**：`FeedDeliveryHealth` 是进程内计数，多实例需逐台看；接指标栈后转发。
4. **引擎未起时前端无提示**：推荐 Tab 空态已给文字提示，但未区分"引擎不可用"与"真的没内容"——可让网关在降级时回传标志位。
