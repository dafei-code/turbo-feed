# 0034 账号处罚域（penalty 包骨架）：与信用分领域分离

- 日期：2026-09-18
- 范围：`tf-gateway`（新增 `service/penalty` 包 + 2 个仓储）+ `deploy/mysql/init-local.sql` + `shardingsphere-config.yaml`
- 关联：[内容安全后续方案](../../turbo-feed-内容安全后续方案.html) 阶段一后续 · 封禁域
- 设计拍板：用户认可「暂不拆独立微服务，但必须把软声誉与硬执行在领域模型上分开」，并指示直接落骨架。

## 1. 动机

P0-5/P0-6 之后，账号侧只有 **软声誉** 一个维度（`account_credit`：信用分 / 等级 / 加严 / 观察期），
缺少抖音式「**硬执行**」那一层：账号封禁。二者生命周期本质不同，混进同一张表会互相腐化：

| 维度 | 信用分（软声誉，已有） | 处罚/封禁（硬执行，本次新增） |
|---|---|---|
| 性质 | 连续、渐变、自动恢复 | 离散、事件驱动、强约束 |
| 生效 | 影响审核口径 / 流量池 | 直接阻断写能力 |
| 审计 | 弱（分数够用） | 强（谁 / 何类目 / 何严重度 / 何来源 / 何处置） |
| 升级 | 无 | 累进：警告 → 临时封 → 永久封 |

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `service/penalty/`（**新建包**） | 5 个枚举：{@code ViolationCategory}(7 类目) / {@code ViolationSeverity}(LOW·MID·HIGH·CRITICAL) / {@code ViolationSource}(机审·举报·人审·翻案) / {@code PenaltyAction} / {@code AccountPenaltyStatus}(NORMAL·WARN·BANNED_TEMP·BANNED_PERM)；2 个实体：{@code ViolationRecord} / {@code AccountPenalty}（record） |
| `service/penalty/PenaltyEscalationPolicy.java` | 配置驱动的升级策略：CRITICAL → 直接封禁（可配永久/临时）；累计违规 ≥ 阈值 → 临时封禁 N 天；其余 → 警告（不阻断写） |
| `service/penalty/PenaltyService.java` | 编排层：`recordViolation()`（写审计 + 升级 + 落处罚态，先评估后写审计以保证 action_taken 真实）、`canWrite()` / `getStatus()` / `isBanned()` |
| `repository/ViolationRecordRepository.java` | append-only 违规记录仓储（id 由 SS 雪花填充，INSERT 不传 id） |
| `repository/AccountPenaltyRepository.java` | 处罚态仓储：`ensure()` / `get()` / `apply()`（ON DUPLICATE KEY UPDATE upsert，避免并发覆盖计数） |
| `config/MediaProperties.java`（`Review`） | 新增 `banTempThreshold`(3) / `banTempDays`(7) / `criticalAutoPermBan`(true) |
| `deploy/mysql/init-local.sql` | 新增 `violation_record_0..3`（append-only，PK(user_id,id)）与 `account_penalty_0..3`（PK user_id）共 8 张物理表 |
| `shardingsphere-config.yaml` | `autoTables` 登记两张逻辑表（分片键 user_id，与 account_credit 同片）；`violation_record.id` 配 snowflake keyGenerateStrategy |

## 3. 关键决策

- **领域分离**：两张新表与 `account_credit` 同键同片（user_id），但**不互相引用**——封禁字段不塞进信用表，信用分逻辑也不碰封禁。`PenaltyService` 只管硬执行，扣分仍由 `AccountCreditService#onViolationConfirmed` 负责，调用方组合。
- **append-only 审计**：`violation_record` 只插入，纠正靠新增 `source=APPEAL_OVERTURN` 反向记录，不抹历史——升级决策的「事实源」必须可追溯。
- **封禁到期用读取时推导**：`canWrite()` 按 `ban_until` 实时判断，临时封禁到点即恢复，**不依赖定时任务跑过**（与 P0-5 加严窗口同手法）。`ban_until` 为空的 `BANNED_TEMP` 属数据异常，按 **fail-closed** 视为仍在封禁中，避免脏数据把被封账号静默放出。
- **升级策略单独成类**：规则最易变（加类目 / 调阈值 / 加灰度），独立后可换成规则表或远端策略服务而不动调用方。
- **⚠️ 本包刻意不用 Lombok**：见第 5 节「构建环境说明」。
- **集成缝未接线**：写路径（上传/评论/改资料）应在落库前调 `canWrite()`；违规确认路径应调 `recordViolation()`。本次只提供 API，**尚未改动任何端点**——封禁是高不可逆操作，正式启用前须先补 P0-3 outbox（当前时间线投递 fail-open，误封无补偿）。

## 4. 验证

- 编译：`mvn -pl tf-gateway -am compile` **BUILD SUCCESS**（11 个新文件，0 error）。
- 产物核对：`target/classes` 下 11 个新类齐全（含 `PenaltyEscalationPolicy$Decision`）；
  `javap MediaProperties$Review` 确认 `getBanTempThreshold/getBanTempDays/isCriticalAutoPermBan` 已生成。

## 5. 构建环境说明（重要，后续改动请注意）

本机（沙箱）对**新加入的文件**存在 **Lombok 注解处理不生效** 的情况：
表现为 `@RequiredArgsConstructor` 的 final 字段报「未在默认构造器中初始化」、`@Slf4j` 的 `log` 报「找不到符号」。
且若强制**全量**重编译（如 `touch` 全部源码），该现象会扩散到既有文件（MediaReviewService / FeedEngineClient / SnowflakeConfig 等）导致 411 个同类错误——
**这是既有环境限制，非本次代码引入**（移走本包文件后复现一致）。

处理：本包 4 个类一律**显式声明构造器与 Logger**，不依赖 Lombok 代码生成；
验证时保持**增量编译**（勿 `touch` 全量源码，否则会触发上述既有问题）。
后续若在本机新增加 Lombok 注解的类，建议同样显式声明。

## 6. 遗留

- **未接线**：写路径 / 违规确认路径尚未调用 `canWrite()` / `recordViolation()`（下一步）。
- **同类计数**：骨架版用「累计总数」判断升级；抖音式「同类 N 次」需把 `violation_count` 改为 JSON 类目计数。
- **多维度封禁**：能发不能评 / 不能直播等需 `ban_scope` 位图，当前仅单一 `status`。
- **128 分片配置**：`shardingsphere-config-128.yaml` 未同步登记这两张表（该方案 P0-7 未启用，切换时需补）。
- **P0-3 outbox**：封禁启用前必须补，否则误封不可逆。
