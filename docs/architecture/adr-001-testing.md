# ADR-001：引入自动化测试（P0-1）

- **状态**：已采纳（Accepted，2026-10-17）
- **所属改进项**：`improvement-plan.md` §3 / P0-1
- **决策人**：dafei（turbo-feed 负责人）
- **关联 ADR**：ADR-002（Nacos）、ADR-003（tf-counter）

## 1. 背景（Context）

截至 `main` @ `0e1403f`，全仓 **2.6 万行 Java、0 个测试文件**（`src/test/java` 不存在），`pom.xml` 无 `spring-boot-starter-test`。

项目的业务架构是上乘的（推拉结合时间线、顺序消息保序、outbox 至少一次、CAS 审核状态机、分片键设计、流量池赛马），但**工程化底线缺失**——这些"正确性逻辑"长期靠 67 条 changelog 踩坑记录保证：

- 审核状态机 6 态流转 + CAS 并发重试（`MediaReviewService`）；
- 顺序消费 `append/remove/promote` 时间线写时物化（`FeedTimelineStore`）；
- `user` 分片键路由 + 手机号路由表消除广播（`UserJdbcRepository` / `UserPhoneRouterRepository`）；
- outbox 中继 CAS 无主补偿（`OutboxRelay` / `OutboxService`）。

**风险**：任何一次重构都可能悄悄回归，且无人能即时发现；CI 无法阻止坏代码合入。这是"敢上生产"的两道坎之一（另一道是 P0-5 Nacos）。

## 2. 决策（Decision）

1. **测试框架**：引入 `spring-boot-starter-test`（JUnit 5 + Mockito + AssertJ）。
2. **集成测试用真存储，不用 H2**：ShardingSphere 5.5.3 在 H2 上的 SQL 解析 / 分片行为与 MySQL 不一致，H2 通过 ≠ 生产通过。真集成测试用 **Testcontainers**（MySQL + Redis）起真实实例。
3. **四靶心先锁核心正确性逻辑**（优先于覆盖率数字）：
   - `MediaReviewService`：审核 CAS 状态机（`PENDING→APPROVED/REJECTED` 终态幂等、CAS 落空不重复投递）、`decideReviewTask` / `report` / `handleReport` 处置闭环；
   - `FeedTimelineStore`：`append` / `remove` / `promote` 的 Redis ZSET 写路径与反查索引；
   - `UserJdbcRepository`：insert→findById 单分片命中、countByPhone 走路由表、resolveUserIdByPhone 广播兜底回填；
   - `OutboxRelay`：中继补偿（空 due 列表提前返回、`tryDeliver` 成功率统计、卡死行回收）。
4. **纯逻辑优先 Mockito 单测**：`MediaReviewService` / `OutboxRelay` / `FeedTimelineStore` 的写路径只依赖 `redisTemplate` / `JdbcTemplate` / 内存对象，**不启 Spring、不需要 Docker**，本地即可跑绿。需真实存储的（`UserJdbcRepository` 分片路由）才走 Testcontainers 集成测。
5. **无 Docker 环境跳过而非失败**：集成测用自定义 `@RequiresDocker` 守卫（基于 `DockerClientFactory.isDockerAvailable()`），本地未起 Docker 时 `mvn test` 只跑单测（绿），CI（有 Docker）跑全量。避免"本地没 Docker 就全盘红"误导。
6. **CI 门禁**：`.github/workflows/ci.yml` 执行 `mvn -B test`，必须通过才允许合并。
7. **覆盖率门槛（渐进）**：核心包（review / timeline / outbox / sharding 路由）≥ 60%，后续逐步提；暂不强制 JaCoCo 卡线（先建基线，避免一次性卡死）。

## 3. 后果（Consequences）

**正面**
- 核心正确性逻辑有回归保护，重构 / 升级（如 P0-5 Nacos、P1-2 tf-counter 接线）不再靠手测；
- CI 可重复验证，合入门槛可量化；
- Mockito 单测零基础设施依赖，开发者本地秒级反馈。

**负面 / 成本**
- 全量集成测需 Docker（CI 提供；本地需 `docker` 在线）；
- 测试维护成本随业务增长；
- 根 pom 需显式加 `maven-surefire-plugin` + `junit-platform-launcher`（本项目 parent 是普通 BOM 父，非 `spring-boot-starter-parent`，不会自动带 JUnit5 运行时）。

## 4. 备选方案（Alternatives Considered）

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| H2 内存库做集成测 | **否决** | ShardingSphere 在 H2 行为不一致，假绿；分片路由 / 顺序消息无法真实验证 |
| 只写纯单测、不做集成测 | **否决** | 分片路由、时间线 ZSET、outbox 补偿本质是"与外部存储交互的正确性"，Mock 不出真实语义 |
| JUnit 4 + Spring Test | **否决** | 项目已全面 JUnit5，混用增加心智负担 |
| JaCoCo 强卡 80% 立刻生效 | **暂缓** | 当前 0 基线，强卡会阻塞所有 PR；先建基线再阶梯提升 |
