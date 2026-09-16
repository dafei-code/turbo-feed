# 0024 · 技术文档全量对齐当前实现（纠正失实描述）

> 日期：2026-09-16
> 范围：**纯文档**。不涉及任何代码、配置与接口签名变更。
> 前置：`0020-feed-engine-split.md` / `0021-feed-timeline-rocketmq.md` / `0022-multi-image-post.md` / `0023-post-review-deadlock-cas.md`
> （本次要解决的正是"代码已推进、文档仍停留在旧版本"）

## 一、背景：文档与实现脱节已到会误导人的程度

`docs/` 是项目声明的**唯一技术文档来源**（Single Source of Truth），但多篇文档停留在 0016~0019 阶段，
与 0020~0023 之后的实现存在**硬失实**。典型三例：

| 文档 | 原描述 | 实际 |
|---|---|---|
| `docs/api/gateway-api.md` | 登录参数 `username`；上传"返回 URL 列表"；审核接口标注**未实现** | 登录按手机号；上传返回**帖子视图**（`postId` + `images`，9 张上限）；审核状态机 + 举报申诉闭环均已落地 |
| `docs/modules/tf-gateway.md` | 审核状态存**内存 `ConcurrentHashMap`、重启即丢** | 早已落 MySQL 分片表，且 0023 已改 CAS + 有界重试 |
| `docs/architecture/sharding.md` | 只描述 `user` 一张表；物理表写作「每库 `_0`/`_1` 各一份」 | 实际 **6 张逻辑表**分片；物理表为全局连续编号 `_0.._3` 后按 `i % 库数` 摊到库 |

**风险等级**：`gateway-api.md` 是对外契约文档，前端按它联调会直接失败。

## 二、核实方法（先取证、再动笔）

所有结论均以**代码/配置实测**为准，不凭印象改写：

1. **模块实现状态**用源文件数判定：`tf-hotspot` 0 个、`tf-benchmark` 0 个、`tf-counter` 仅 1 个（启动类）
   → 三者均为**模块骨架**；`tf-gateway` 87 个、`tf-feed-engine` 5 个、`tf-shared` 4 个为真实现。
2. **版本号**取自 `pom.xml` 实际声明，不使用"3.4.x"这类模糊值。
3. **端点清单**由 `@GetMapping` / `@PostMapping` / `@RequirePermission` 注解**全量枚举**得出，
   不依赖旧文档。
4. **分片物理表名与落库规则**以 `deploy/mysql/init-local.sql` 头部约定 + `shardingsphere-config.yaml`
   的 `autoTables` / `sharding-count` 为准。
5. **错误码全量表**直接从 `ErrorCode.java` 枚举读取。
6. **接口是否要求登录**由控制器是否使用 `UserContextHolder` 判定，不靠推测。

> 关键纪律：本轮**只改与当前进度不符的部分**，保留原有章节结构、行文风格与深度，
> 不趁机重写无关内容。

## 三、逐篇改动

### 1. `README.md`（仓库根）
- 状态行改写：写明 B1（读模型真拆）/ B2（顺序消息投递）已落地、一帖多图全链路跑通；
  三个骨架模块**单列标注**。
- 模块结构树：骨架模块统一加「—— 模块骨架，待实现」；**纠正 Feed 引擎职责**为「公域时间线读模型」
  （原文写的"收件箱 / 大V outbox / 多路归并"是未实现的 fanout 蓝图）。
- 服务间通信：补"`append` / `remove` 必须共用同一 topic 与消费者"的理由与未启用 MQ 时的兜底路径。
- 技术栈：换成 pom 实测版本，并注明 **Redisson / Caffeine / H2 只在 tf-counter**，不再写成全局在用；
  `docker-compose.yml` 实测**只含 RocketMQ**（不含 Redis / MinIO / MySQL）。
- **快速开始修正（阻塞级）**：原文档照抄**跑不起来**——雪花实例标识
  `TURBOFEED_SNOWFLAKE_WORKER_ID` / `TURBOFEED_DATACENTER_ID` **无默认值、缺失即启动失败**，
  原文一字未提。已补必需环境变量表 + 依赖清单 + 初始化脚本（含新增的 `migrate_post_group.sql`）+ 常用开关。

### 2. `docs/api/gateway-api.md`（全量重写）
按注解枚举的真实端点重写：登录换取 JWT、上传返回**帖子视图**、`/api/media/mine` **一帖一行**、
审核/举报/申诉全量端点与所需权限、统一返回结构、**完整错误码表**、curl 示例。

### 3. `docs/modules/tf-gateway.md`
保留原有深度（上传链路、校验责任链、限流），更新失实部分：真实包与类清单结构树、
一帖多图与整帖一审、审核状态机与 CAS、RBAC（`@RequirePermission` + `PermissionInterceptor`）、
举报申诉闭环、失效的配置表。

### 4. `docs/modules/tf-feed-engine.md`（重写）
原文自称「设计骨架」，但它已有 5 个真实源文件且**已在承载线上读路径**，性质写错了。
改写为**公域时间线读模型**：Redis ZSET 的多池键结构、反查索引、推荐流旁路缓存与 TTL、
内部接口路径；并如实说明 **fanout 蓝图只留了 5 个空包（`.gitkeep`）、无任何实现**。

### 5. `docs/modules/tf-shared.md`（重写）
原文只有 `Result` / `ErrorCode`，且把 Feed 契约列为"演进规划"——实际 **`FeedItemView` /
`FeedTimelineEvent` 已落地**。补：模块现状表、**`-parameters` 编译配置的必要性**、
错误码全量（12 条，含 `ACCOUNT_NOT_REGISTERED` / `UPLOAD_IN_PROGRESS` / `SENSITIVE_WORD_HIT`）、
两个契约的字段与设计理由，以及**零依赖下的命名纪律**（禁用 `is` / `get` 前缀方法，
与 `Result.isSuccess()` 同类陷阱）。

### 6. `docs/architecture/sharding.md`（重写）
- 补全 **6 张逻辑表**及其分片键选型理由（`user.id` / `media.user_id` / `account_credit.user_id` /
  `report·appeal·comment.media_id`），说明 `comment` 与 `media` 分片键**正交**是刻意设计；
- **纠正物理表命名**：不是"每库各建两份 `_0`/`_1`"，而是全局 `_0.._3` 再按 `i % 库数` 落库，
  并给出 `init-local.sql` 的"请勿改"警示；
- 补 `autoTables` 约束（HASH_MOD 是**自动分片算法**，手动 `tables` 会直接报错）；
- **纠正 `user_phone_router` 的状态**：原文写"新增路由表承担全局唯一与 phone→uid 定位"，
  实际该表**并未启用**（仅在 `user_schema.sql` 留了方案说明，`init-local.sql` 明确不建，
  `UserJdbcRepository` 注释写明"本 demo 阶段不引入"），当前是
  **单分片 `uk_phone` 防护 + 跨分片广播 `COUNT` 查重**；
- 扩容路径补充 **128 物理表方案已就绪但未切换**（`shardingsphere-config-128.yaml` +
  `expand_media_tables_128.sql`），并强调直接切配置会路由错乱、必须迁移再平衡。

### 7. `docs/architecture/overview.md`
**修正硬错误**：RocketMQ 版本写作 `2.3.4`，pom 实为 **`2.3.5`**。同步模块状态与技术栈。

### 8. `docs/ops/deployment.md`
原文"克隆即跑，零外部依赖"**已不成立**：补必需环境变量、中间件依赖、
生产前必改清单（`shardingsphere-config.yaml` 内的裸密码为"克隆即跑"专用）。

### 9. `docs/README.md`
索引树**补漏 `architecture/sharding.md`**，并修正各篇描述使其与本文档同步结果一致。

### 10. `docs/modules/tf-hotspot.md`
原文状态说明**埋在正文第 27 行**，头部只写"热点探测与治理 SDK"，易被误读为已实现。
按 `tf-counter.md` 的风格在头部补状态行：**模块骨架，业务未实现（源文件 0 个）**，
并说明 pom 依赖已就位、已被 tf-counter / tf-feed-engine 声明依赖但**尚无代码调用**。

## 四、未改动（刻意）

- **`docs/changelog/*.md` 是历史快照，不回头改**。例如 `0003` 里的端口 `8082`
  是当时的真实值（后续 `f5c14b6` 才改 8083），改了反而破坏记录的可信度。
- `docs/architecture/service-split.md` 经核对**已含 B1/B2 最新信息**（8083、顺序消息等），无需改动。

## 五、验证

- 改动范围：`git status` 显示**仅 10 个 markdown 文件**被修改，**无任何代码/配置文件**；
- 交叉一致性：`README.md` 与 `docs/` 对模块状态、端口、版本的表述已对齐同一组实测值；
- 反向检索：以「`一帖多图` / `postId` / `ReviewOutcome` / `updateStatusCas`」检索 `docs/`，
  原先**零命中**，现已覆盖到 `gateway-api.md` / `tf-gateway.md` / `tf-shared.md` / `sharding.md` /
  `tf-feed-engine.md` / `deployment.md`。

## 六、遗留

- `tf-hotspot` / `tf-counter` / `tf-benchmark` 三篇仍是**蓝图文档**，实现落地时需同步更新；
- 各文档的**版本号/配置键**仍靠人工同步，尚无自动化校验（可考虑加一个文档一致性检查脚本）。
