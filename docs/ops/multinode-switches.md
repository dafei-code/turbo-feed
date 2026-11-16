# 多副本开关清单（阶段 B1）

> **状态：开关清单 + 三个硬前置的代码/编排改动已落地；运行时开关仍默认全关（注入才生效）；真启动验证被本机 Docker 未运行阻塞。**
> 已新增 `tf-counter/src/main/resources/application-prod.yml`（prod 切共享 MySQL）与 `deploy/compose/mysql/01-counter-shared-db.sql`（共享库三表），并修改 `deploy/compose/docker-compose.prod.yml`（initdb 挂目录、三段变量转发、counter `depends_on mysql`）。**未改任何 Java 代码**；三个 B1 开关在 prod profile 与 compose 里的默认值都是 `false`，不注入时行为与改动前一致。本轮未启动服务或容器，见 §3.3。

## 1. 核查结论：原清单要改两处

原计划「把已有的 opt-in 开关全部打开」，读码后发现两处必须修正：

| 原计划 | 实际情况 | 处理 |
| --- | --- | --- |
| 打开 `hotkey-cache.broadcast-enabled` | `tf-feed-engine/application.yml:110-112` 写死 `false`，注释明确：热点榜 ZSET 每次行为事件都更新，照搬「每次变更广播」会淹没 pub/sub，**按设计只用 5s 短 TTL 失效** | **从清单移除**，保持 false。这是设计决策，不是漏配 |
| 打开 `state-cache.broadcast-enabled` | `tf-gateway/application.yml:315` 已是 `${TURBOFEED_STATE_CACHE_BROADCAST_ENABLED:true}`，默认就开 | 模板里显式写出便于核对，但无需改动 |

另外发现一个原先没列进 B1 的**真缺口**：`tf-counter` 没有 `application-prod.yml`，数据源写死 `jdbc:h2:mem:tf-counter`，`shedlock` / `post_counter` / `counter_flush_dedup` 三张表都在各副本自己的内存库里——多副本下 ShedLock 选主根本不共享。详见 §3.1。

## 2. 开关矩阵与精确映射

属性名必须与 `FeatureFlagKeys` / `@ConditionalOnProperty` **逐字一致**；`FeatureFlagGuard` 的类注释记录了历史事故（`turbofeed.counter.redis.enabled` 写成点号 → 不报错、开关不动的静默 no-op）。环境变量到属性的 relaxed binding 由 Spring 的 `SystemEnvironmentPropertySource` 完成：`.` / `-` 一律转 `_` 并大写。

| 环境变量 | 属性键 | 默认 | B1 目标 | 依据 |
| --- | --- | --- | --- | --- |
| `TURBOFEED_REDIS_MODE` | `turbofeed.redis.mode` | `single` | `cluster` | gateway `:300`、engine `:85` 已有占位符 |
| `TURBOFEED_REDIS_CLUSTER_NODES` | `turbofeed.redis.cluster-nodes` | 空 | 节点列表 | `FeatureFlagGuard.validateRedisTopology()`：cluster 无节点直接 fail-fast |
| `TURBOFEED_REDIS_REDISSON_ENABLED` | `turbofeed.redis.redisson-enabled` | 缺失/false | `true` | `FeatureFlagKeys.REDIS_REDISSON_ENABLED`；`RedissonTopologyConfig:40` 的 `@ConditionalOnProperty` |
| `TURBOFEED_SNOWFLAKE_LEASE_ENABLED` | `turbofeed.snowflake.lease-enabled` | `false` | `true` | gateway `:133` 占位符；`SnowflakeConfig` / `WorkerIdLeaseManager` 已实现 |
| `TURBOFEED_SNOWFLAKE_DATACENTER_ID` | `turbofeed.snowflake.datacenter-id` | 空 | 仍必填 | 租约只解决 worker-id，datacenter-id 必须逐实例配 |
| `TURBOFEED_COUNTER_REDIS_ENABLED` | `turbofeed.counter.redis-enabled` | `false` | `true` | `tf-counter/application.yml:44` |
| `TURBOFEED_COUNTER_REDIS_BUFFER_ENABLED` | `turbofeed.counter.redis-buffer-enabled` | `false` | `true` | `tf-counter/application.yml:45`；同时激活 `ShedLockConfig` |
| `TURBOFEED_COUNTER_ENABLED` | `turbofeed.counter.service.enabled` | `false` | `true` | gateway `:323` 占位符，网关侧计数增量总闸 |
| `TURBOFEED_STATE_CACHE_BROADCAST_ENABLED` | `turbofeed.state-cache.broadcast-enabled` | `true` | 保持 `true` | gateway `:315` |

**组合约束**（违反即启动期 fail-fast，不是静默降级）：

1. `redis-buffer-enabled=true` ⇒ `redis-enabled=true` ⇒ `redisson-enabled=true`（`FeatureFlagGuard.validateCounterBuffer()` + `RedisCounterBuffer` 类注释）。
2. `redis.mode=cluster` ⇒ `cluster-nodes` 非空；`sentinel` ⇒ `sentinel-master` 非空。
3. `snowflake.lease-enabled=true` ⇒ Redis 可达；`lease-renew-interval-seconds(20) ≤ lease-ttl-seconds(60)/2` 已满足。续约失败会 fail-closed 停止发号，这是刻意设计。
4. Redis 拓扑 **三个服务必须一起切**：engine `application.yml:84` 注释记录了「只改一侧 → 时间线读写静默失败、推荐流全空」。
5. `tf-counter` 的 `shedlock` 表由 `src/main/resources/schema.sql` 的 `CREATE TABLE IF NOT EXISTS` 建立，H2/MySQL 通用；但**表存在 ≠ 锁共享**，见下节。

## 3. 三个硬前置（①② 已落地，③ 受本机环境阻塞）

### 3.1 tf-counter 没有共享数据源 —— 真缺口，属于代码改动

`tf-counter/src/main/resources/` 只有 `application.yml` / `logback-spring.xml` / `schema.sql`，**没有 `application-prod.yml`**；`application.yml` 写死 H2 内存库：

```yaml
spring.datasource.url: jdbc:h2:mem:tf-counter;MODE=MySQL
spring.sql.init.mode: always
```

prod compose 给 counter 设了 `SPRING_PROFILES_ACTIVE: prod`，但该服务没有对应 profile 文件，等于没生效。后果：

| 表 | 多副本下的后果 |
| --- | --- |
| `shedlock` | `JdbcLockProvider` 各锁各的，选主失效，多个副本同时 drain |
| `post_counter` | 各副本各写各的内存库，计数不共享 |
| `counter_flush_dedup` | 同批次去重登记不共享，重放时会重复累加 |

共享 Redis 缓冲只能保证「增量不丢」，**补不上锁与结果表的共享**。因此 `redis-buffer-enabled=true` 之前必须先给 counter 接共享 MySQL（新增 `application-prod.yml` 或外部化数据源变量 + 迁移脚本），并确认 `schema.sql` 的三张表在共享库里建好。**已落地**：新增 `tf-counter/src/main/resources/application-prod.yml`，prod profile 下数据源切共享 MySQL（`spring.sql.init.mode: never`，表由迁移脚本管理，避免多副本启动并发 DDL）；新增 `deploy/compose/mysql/01-counter-shared-db.sql` 建 `turbo_feed_counter` 库与三张表，表定义与 `schema.sql` 已校验逐字一致；prod compose 的 mysql 段改为挂整个 `./mysql` 目录（原单文件挂载会漏掉新脚本），counter 段补 `depends_on: mysql: service_healthy` 与 DB 凭据转发。凭据沿用已有的 `TURBOFEED_DB_USERNAME` / `TURBOFEED_DB_PASSWORD`，未新增敏感值；counter 的 pom 已有 `mysql-connector-j`，无需改依赖。

⚠️ `docker-entrypoint-initdb.d` 只在**空数据卷首次初始化**时执行，已有环境必须手工跑 `01-counter-shared-db.sql`，重启容器不会自动补建。

### 3.2 compose 的 environment 是白名单

`deploy/compose/docker-compose.prod.yml` 与根 `docker-compose.yml` 的 `environment:` 段逐条显式列出变量，**未列出的变量不会进容器**。这正是 `TURBOFEED_BEHAVIOR_LOG_ENABLED` 那个已记录过的坑（设了、日志无异常、数据一条没有）。

要生效，需在 `tf-gateway` / `tf-feed-engine` / `tf-counter` 三段各补类似转发，且默认值保持关闭以免破坏「克隆即跑」：

```yaml
TURBOFEED_REDIS_REDISSON_ENABLED: ${TURBOFEED_REDIS_REDISSON_ENABLED:-false}
TURBOFEED_SNOWFLAKE_LEASE_ENABLED: ${TURBOFEED_SNOWFLAKE_LEASE_ENABLED:-false}
TURBOFEED_COUNTER_REDIS_ENABLED: ${TURBOFEED_COUNTER_REDIS_ENABLED:-false}
TURBOFEED_COUNTER_REDIS_BUFFER_ENABLED: ${TURBOFEED_COUNTER_REDIS_BUFFER_ENABLED:-false}
```

**已落地**：prod compose 三段都补了 `:-false` 默认值的转发——gateway 3 条（redisson / snowflake 租约 / counter 总闸）、engine 1 条（redisson）、counter 8 条（DB 凭据 + Redis 拓扑与口令 + 3 个开关）。默认值全关，不注入时与改动前逐字节一致。另需注意：counter 段原先连 Redis 拓扑变量都没有，会连 `127.0.0.1:6379`，本轮一并补齐。

若还要覆盖 `TURBOFEED_COUNTER_DB_URL`，需在 counter 段再加一条转发；当前用 `application-prod.yml` 的默认值 `mysql:3306/turbo_feed_counter`。

### 3.3 Redis 集群在线

`deploy/redis/docker-compose-cluster.yml` 提供 7000-7005 的 3M3S，`TURBOFEED_REDIS_CLUSTER_NODES` 需填真实可达地址（`cluster-announce-ip` 必须是真实 LAN IP，不能用 127.0.0.1）。**本轮真启动验证未执行**：探测 3306 / 3307 / 6379 / 7000-7005 / 9000 全部未监听，且 `docker info` 返回 `Cannot connect to the Docker daemon`——本机 Docker Desktop 未运行。起 Redis 集群 + MySQL + 双实例属于创建容器/启动服务，需 Docker 就绪后单独执行，见 §5 验证口径。本轮只完成配置改动 + 编译（`-Plocal -pl tf-counter -am compile` 通过，新 profile 已打包进 `target/classes`）+ 静态校验。

## 4. 启用步骤（前置解决后）

1. `cp deploy/env/.env.multinode.example deploy/env/.env.multinode`，按需改值；口令仍走 prod 已有的 `TURBOFEED_REDIS_PASSWORD`，不放进本模板。
2. 确认 §3.1 的共享库脚本已在目标 MySQL 执行（空数据卷自动建，已有库需手工跑）；§3.2 的 compose 转发已接线，无需再改。
3. 起 Redis 集群 → 起应用（prod profile）→ 用阶段 A 的 Loki 按 `instanceId` / `traceId` 查日志。
4. 逐组放量，别一次全开：先 Redis 拓扑 + Redisson → 再 Snowflake 租约 → 最后 counter 共享缓冲 + ShedLock + 网关总闸。

## 5. 验证口径

| 检查 | 期望 | 怎么看 |
| --- | --- | --- |
| `FeatureFlagGuard` 不 fail-fast | 三个服务正常启动 | 启动日志无「危险组合」中文报错 |
| worker-id 不冲突 | 两实例拿到不同 worker-id，发号无重复主键 | Redis 里看租约 key；按 `instanceId` 查日志 |
| ShedLock 只有一个 leader | 同一时刻只有一个副本执行 flush | `shedlock` 表 `locked_by` + 阶段 A 日志按 `cycleId` 串一轮调度 |
| 计数不丢不重 | 两实例并发点赞后 `post_counter` 增量正确 | `counter_flush_dedup` 同批次只登记一次 |
| 缓存收敛 | 改账号状态后另一实例立即可见 | 阶段 A 日志按 `traceId` 看失效广播消费记录 |
| Redis 拓扑一致 | 三服务都连集群 | 启动日志；engine 侧推荐流不空 |

阶段 A 的集中日志在这里是**验证基础设施**：没有它，B1 的收敛行为只能靠各副本本地日志按时间戳手工对齐。

## 6. 回滚

- 开关全部由环境变量控制，去掉注入即回到默认（`single` / 全 false）。
- §3.2 的 compose 转发用 `:-false` 默认值，即使不注入也保持现状。
- counter 数据源改动需单独回滚（切回 H2 会丢内存库计数，仅在开发环境这么做）。
- 删除本模板与文档不影响任何运行时行为。

## 7. 与 B2 的边界

B1 只打开**代码里已存在**的 opt-in 能力，不改 Java 语义。B2（词库 / 白名单跨实例 epoch + 广播失效）要动 `SensitiveWordService` / `WhitelistService` 的 `triggerReload()` 单飞 CAS 与「追平 3 轮」路径，属于另一个量级，单独给方案再确认。

§3.1 的共享数据源改动已随 B1 落地（新增 profile + 迁移脚本 + compose 接线），不属于 B2 范围；B2 只处理词库/白名单的 epoch 与广播失效。
