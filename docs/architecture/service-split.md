# 服务拆分方案（Service Split Plan）

> 状态：2026-08-28 已落地结构拆分；引擎业务实现按迭代路线逐模块填充。
> 进展：**B1 已落地**——Feed 时间线读模型迁入 tf-feed-engine，网关改远程调用 + 降级（见 §8）。
> 进展：**B2 已落地**——Feed 时间线投递从同步 HTTP 改为 RocketMQ 顺序消息（append/remove 共用单 topic、ORDERLY 消费，见 §9）。
> 变更记录：见 [docs/changelog/](../changelog/)，每次变更一份 md。

## 1. 背景与动机

拆分前 tf-gateway 以 Maven 依赖方式内嵌 tf-counter / tf-feed-engine / tf-hotspot，单进程运行。问题：

1. **传递依赖污染**：gateway 进程被迫加载 Redisson / JDBC 等引擎专属重依赖（已踩过 Redisson 自动配置导致启动失败的坑，临时用 `<exclusion>` 压制）；
2. **无法独立伸缩**：计数与 Feed 扇出的负载特征完全不同，单进程无法按需扩容；
3. **故障域不分**：引擎 OOM / GC 停顿会拖垮 HTTP 接入层；
4. **与目标架构不符**：工业级形态应为"接入层 + 三大引擎各自独立部署单元"。

## 2. 拆分原则

- **部署单元 vs 进程内库**：有独立伸缩 / 故障隔离诉求的才拆成服务；天然嵌入进程的（热点治理 L1 本地缓存 + 广播）保持库形态。
- **契约归 tf-shared**：跨服务传输的模型（Result / ErrorCode / 事件 DTO）只放 tf-shared，保持零依赖。
- **克隆即跑**：无 Redis / MQ 的环境也能启动全部服务（自动配置条件化 / 暂排除，就绪后移除）。

## 3. 拆分结果

| 模块 | 形态 | 端口 | 职责 | 关键依赖 |
|---|---|---|---|---|
| tf-gateway | 独立服务（可执行 jar） | 8080 | HTTP 接入：认证 + 媒体上传 + Sentinel 流量防护 + 审核状态机。**不持有任何 Feed 读模型** | web / sentinel / rocketmq / actuator / shardingsphere-jdbc |
| tf-counter | 独立服务（可执行 jar） | **8081** | 分布式计数：Redis 分桶 + MQ 削峰 + 批量落库 + 三级缓存 | redisson / jdbc / h2 / caffeine |
| tf-feed-engine | 独立服务（可执行 jar） | **8083** | Feed 推拉核心：**公域时间线读模型（多池 ZSET + 反查索引 + 推荐流旁路缓存）** / 收件箱 / outbox / 活跃度分层 / 多路归并 | data-redis(lettuce) / caffeine |
| tf-hotspot | **进程内 SDK（库）** | - | 热 Key 探测 + 广播 + 本地 L1 缓存，嵌入 counter / feed-engine 进程 | caffeine |
| tf-shared | 契约库 | - | Result / ErrorCode / 跨服务 DTO（如 FeedItemView） | 零依赖 |
| tf-benchmark | 压测模块 | - | 压测器 + 报告生成 | 独立 |

## 4. 通信矩阵

| 调用方 → 提供方 | 方式 | 落地状态 |
|---|---|---|
| gateway → feed-engine（读） | 同步 HTTP（RestClient + 显式超时） | **已落地**：`GET /internal/feed/recommended`，网关侧 `FeedEngineClient` |
| gateway → feed-engine（写） | 同步 HTTP（RestClient + 超时，fail-open） | **已落地**：`POST /internal/feed/timeline/{append,remove}` |
| gateway → counter | 同步 HTTP | 未落地（计数尚未拆出） |
| gateway → 引擎（事件类） | 异步 RocketMQ（`turbofeed.mq.enabled` 条件切换，本地事件兜底） | 事件总线已在 gateway 落地；**B2 已落地：Feed 时间线投递改 RocketMQ 顺序消息**（见 §9），`turbofeed.mq.enabled=false`（默认/缺失）走 HTTP 兜底 |
| 引擎 → 引擎 | 异步事件为主，避免同步链路放大故障 | 按业务出现时定 |

**服务发现**：当前直连 `host:port`（`turbofeed.feed.engine.base-url`）；Phase 2 引入 Nacos 后该配置退化为兜底。

**内部接口约定**：引擎侧服务间接口统一挂 `/internal/**`，与对外 `/api/**` 物理区分，便于在网关/Ingress 层直接拒绝外部访问。

## 5. 环境约束处理（克隆即跑）

- **Redis**：tf-feed-engine 已补 `spring.data.redis.*`（Lettuce 懒连接，未启动 Redis 不影响启动）；
  ⚠️ 注意 `redisson-spring-boot-starter` 会把 `spring-boot-starter-data-redis` 里的 `lettuce-core` / `jedis`
  一并 exclude，只留自动配置不留客户端实现——引擎因此改为直接声明官方 `spring-boot-starter-data-redis` + `commons-pool2`；
- **MQ 暂缺**：RocketMQ 发布器 / 消费者由 `turbofeed.mq.enabled=true` 才激活，默认本地 Spring 事件；
- **DB 暂缺**：tf-counter 用 H2 内存库兜底，生产切 MySQL。引擎**不依赖 DB**（时间线全在 Redis）。

## 6. 验证方式

```bash
mvn clean package
java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar       # 8080
java -jar tf-counter/target/tf-counter-0.1.0-SNAPSHOT.jar       # 8081
java -jar tf-feed-engine/target/tf-feed-engine-0.1.0-SNAPSHOT.jar  # 8083

curl http://localhost:8080/actuator/health   # UP
curl http://localhost:8081/actuator/health   # UP
curl http://localhost:8083/actuator/health   # UP

# 引擎内部接口自检（无需网关，需 Redis）
curl "http://localhost:8083/internal/feed/recommended?page=0&size=20"
curl -X POST "http://localhost:8083/internal/feed/timeline/remove?mediaId=media/1/x.jpg"
```

## 7. 演进路线

| 阶段 | 内容 |
|---|---|
| 已完成 | 结构拆分：3 个独立可执行服务 + 端口规划 + actuator 健康检查 |
| 已完成 | **B1：Feed 时间线读模型迁入 tf-feed-engine**；gateway 引 HTTP 客户端（RestClient 直连 + 超时 + fail-open）；契约 `FeedItemView` 下沉 tf-shared；降级口径 `turbofeed.feed.degraded-mode` |
| 已完成 | **B2：Feed 时间线投递改 RocketMQ 顺序消息**；gateway 侧 `FeedTimelinePublisher` 双实现（HTTP 兜底 / MQ 顺序），引擎侧 `FeedTimelineConsumer` 走严格写入入口（异常上抛触发重试/DLQ）；9 处调用点全部经 publisher |
| 下一步 | traceId 全链路日志（Phase 1 方案已定）；计数（tf-counter）写路径与 DB 拆分 |
| Phase 2 | Nacos 服务发现 + LoadBalancer + Prometheus / Grafana + Loki 日志集中 |
| Phase 3 | 多机房容灾与降级预案 |

## 8. B1 落地明细（Feed 时间线迁出网关）

**为什么先切 Feed**：它是唯一同时满足"读多写少、负载特征与接入层完全不同、且已经写好了独立读模型"的模块——
拆分收益最大、改动面最小。计数（tf-counter）涉及写路径与 DB，留到下一刀。

**搬迁内容**：

| 组件 | 原位置 | 现位置 |
|---|---|---|
| FeedTimelineStore（多池 ZSET / 反查索引 / 精确下架） | `gateway.service.feed` | `feedengine.timeline` |
| 推荐流旁路缓存（`tf:feed:rec:*`，TTL 15s） | `MediaQueryService`（网关） | `RecommendedFeedService`（引擎） |
| 读接口 `/api/feed/recommended` | 网关直接读 Redis / 回源 DB | 网关**同名保留**（前端零改动），内部转发 `/internal/feed/recommended` |
| 写投递（append / remove，共 9 处调用点） | 网关本地写 Redis | `FeedEngineClient` → `/internal/feed/timeline/*` |

**关键设计决策**：

1. **缓存随数据走**：推荐流缓存从网关挪到引擎。两层缓存会带来"各自过期、失效要跨进程通知"的复杂度，
   拆分的一个意义就是让读模型与其缓存同域。
2. **两种"空"必须区分**：引擎正常但没有内容（`Optional.of(空列表)`）与引擎不可用（`Optional.empty()`）是两回事——
   否则引擎抖动会把"假空"写进缓存或触发无谓回源。客户端用 `Optional` 显式表达该差异。
3. **降级默认不回源**：`degraded-mode=empty`（默认）返回空列表 + WARN；`local-scan` 才回源
   `MediaApprovedGlobal` 广播查全部分片。把跨分片广播挂在生产故障路径上，等于用一次引擎抖动引爆存储层。
4. **契约不吞 Result 的坑**：`Result.isSuccess()` 会被序列化成 `"success":true`，但它既无字段也无 setter，
   反序列化时属未知属性。Boot 默认关闭 `FAIL_ON_UNKNOWN_PROPERTIES` 所以能跑，但打开即全空——
   客户端因此显式用 `JsonNode` 取 `code`/`data`，不依赖 Boot 的容错默认值，也不改动已发布的 `Result` 结构。
5. **契约归 tf-shared 且保持零依赖**：`FeedItemView` 放在 tf-shared，其 `pom.xml` **单独**开启
   `maven-compiler-plugin` 的 `-parameters`，使 Jackson 能按 record 形参名反序列化，
   从而无需引入 `jackson-annotations`。字段名与网关原 `MediaItem` 逐字一致，**历史 Redis 数据无需迁移**。

**已知取舍（B1 明确接受）**：

- 写侧（B1 时期）为**同步 HTTP + fail-open**：引擎抖动时内容会静默不入流。语义与拆分前"网关本地写 Redis 失败仅告警"
  等价，不构成回归；**该静默丢失窗口已被 B2 的 RocketMQ 顺序消息投递消除**——MQ 不可用 → 发布器 fail-fast 抛异常 → 审核/删除事务回滚 → 对外可见失败，而非悄悄不入流。
- 引擎 `/internal/**` 当前**无鉴权**，依赖内网信任与网络隔离；生产需补服务间令牌或 mTLS。

## 9. B2 落地明细（Feed 时间线投递改 RocketMQ 顺序消息）

**解决的问题**：B1 写侧是"同步 HTTP + fail-open"，引擎抖动时内容静默不入流（无重试、无削峰、调用方拿不到失败）。
B2 把 append / remove 改成 RocketMQ 可靠投递，消除该静默丢失窗口。

**事件契约**：`tf-shared` 新增 `FeedTimelineEvent`（零依赖 record，无 Jackson 注解，靠 `-parameters` 反序列化）：
`mediaId / action / poolLevel / item(FeedItemView) / occurredAt`，常量 `ACTION_APPEND` / `ACTION_REMOVE`，
工厂 `append(item, poolLevel)` / `remove(mediaId)`（remove 时 item 为 null）。
**刻意不提供 `isAppend()` / `isRemove()` 这类 is 前缀布尔方法**：record 的布尔方法会被 Jackson 当作 getter
序列化出多余的 `"append":true,"remove":false` 字段，污染消息格式并埋下严格反序列化失败的隐患
（与 `Result<T>.isSuccess()` 同类问题）。判断动作请直接比较 `ACTION_APPEND` / `ACTION_REMOVE` 常量。

**顺序性（关键，不可妥协）**：append 与 remove **共用同一 topic `turbofeed-feed-timeline`、同一消费者**，
靠消息体 `action` 区分，**绝不按 action 拆 tag / 拆消费者**——否则同一 mediaId 的 append 与 remove 会落不同队列、
失去顺序保证，出现「remove 先执行、append 后执行 → 已下架内容重新出现」的内容安全事故。
发送侧 `RocketMQTemplate#syncSendOrderly(destination, payload, hashKey=mediaId)`，
消费侧 `@RocketMQMessageListener(consumeMode=ConsumeMode.ORDERLY)`，保证同一 mediaId 严格按序。

**双路径（`turbofeed.mq.enabled` 条件切换）**：gateway 侧抽象 `FeedTimelinePublisher`，
两个实现互斥激活（`@ConditionalOnProperty`）：
- `HttpFeedTimelinePublisher`（`havingValue=false`，`matchIfMissing=true`，默认/缺失走此）：委托原 `FeedEngineClient` 同步 HTTP，保留 B1 兜底，克隆即跑无 MQ 也能启动；
- `RocketMqFeedTimelinePublisher`（`havingValue=true`）：`FeedItemMapper` 把 `MediaItem` 转 `FeedItemView`，
  构造 `FeedTimelineEvent`，`syncSendOrderly` 投递。**fail-fast（关键）**：投递失败直接抛异常、不写任何降级/本地兜底，
  由审核 / 删除路径的 `@Transactional` 回滚保证不丢——降级会掩盖失败，让 MQ 的持久化/重试/削峰全部失效。
- 9 处调用点（MediaReviewService 5 次 append + 3 次 remove、MediaUploadService 1 次 remove）全部改为经 `FeedTimelinePublisher`，
  仅 `HttpFeedTimelinePublisher` 仍直接调 `FeedEngineClient.append/remove`；读路径 `FeedEngineClient.recommended` 保留。

**引擎严格写入入口（必须做，否则 B2 白做）**：`FeedTimelineStore.append/remove` 仍是 fail-open（HTTP 兜底路径复用），
新增 `appendStrict(item, poolLevel)` / `removeStrict(mediaId)` 把写逻辑抽成「会抛」实现——不做 try/catch，异常向上抛。
`FeedTimelineConsumer` 只调严格入口并 `log.error` 后原样 rethrow；配合 `maxReconsumeTimes=5`，
消费失败由 RocketMQ 重投、重投耗尽进死信队列 `%DLQ%{consumerGroup}`（人工/巡检对账）。
若消费者误用 fail-open 的 `append/remove`，异常被吞、重试与 DLQ 永不触发，静默丢失只是从 HTTP 搬到 MQ（等于白做 B2）。
`append` 无声返回（item 为空/未过审不入流）在严格路径同样保留。

**配置**：引擎 `pom.xml` 引 `rocketmq-spring-boot-starter`（版本由 root pom dependencyManagement 锁定 2.3.5）；
`application.yml` 加 `turbofeed.mq.enabled: ${TURBOFEED_MQ_ENABLED:false}` + `feed.timeline.topic/consumer-group`（默认占位），
`application-mq.yml` 设 `turbofeed.mq.enabled: true` 并配 `rocketmq.name-server`（引擎只消费、无 `producer.group`）。
`rocketmq.*` 必须留在 profile 文件——`RocketMQAutoConfiguration` 的 `@ConditionalOnProperty(value="name-server")` 无 `havingValue`，
缺失则不装配，不会因默认文件带空值而误启。
