# turbo-feed

短视频 Feed 核心系统 —— 推拉结合 Feed 流 + 分布式计数服务 + 热点治理三件套。

> 状态：微服务结构拆分完成（3 个独立可执行服务）。Feed 读模型与时间线投递已真拆至引擎——网关不再持有任何
> Feed 读模型，只做「接入 + 审核状态机 + 降级决策」；网关侧「上传（一帖多图 1..9 张）→ 审核状态机 → 公域入流」
> 全链路已跑通并有端到端用例覆盖。
>
> **各模块的真实完成度以下面「模块结构」逐项标注为准**。其中 `tf-benchmark` 是空壳：
> 本仓库目前**不提供压测套件，也不提供可复现的性能报告**（此前首段曾如此声称，属过时宣传，已订正）。

## 模块结构

```
turbo-feed/
├── tf-shared/         契约库：跨服务传输模型（Result / ErrorCode / FeedItemView / FeedTimelineEvent），零依赖
├── tf-redis/          Redis 接入单一事实源（进程内库）：拓扑装配 单机/哨兵/集群 + 连接池，引入即生效
├── tf-hotspot/        热点治理 SDK（进程内库）：L1 本地缓存 + Redis pub/sub 失效广播 —— 已落地（AccountStateCache / HotTimelineKeyCache 在用）；热 Key 探测（滑动窗口）待实现
├── tf-feed-engine/    Feed 引擎（独立服务 :8083）：公域时间线读模型 —— 多池 ZSET + 反查索引 + 推荐流旁路缓存
│                      + 存量补投消费端（`/internal/feed/timeline/*`），需与 tf-gateway 同时启动
├── tf-counter/        计数服务（独立服务 :8081）：Redis 分桶 + MQ 削峰 + 批量落库 + 三级读缓存(L1 Caffeine/L2 Redis/L3 DB) —— 已落地
├── tf-gateway/        HTTP 接入层（独立服务 :8080）：认证/RBAC + 媒体上传(一帖多图) + 审核状态机 + 关注关系(follow/fans) + Sentinel 流量防护
├── tf-benchmark/      压测模块 —— **未实现**（0 个 Java 源文件，仅目录占位）。
│                      本仓库不提供压测套件与性能报告；结论与删除条件见 docs/modules/tf-benchmark.md
└── turbo-feed-ui/     静态前端（纯 HTML，无构建）：登录/注册 + 发布 + 公域 Feed(整帖轮播) + 审核 / 管理工作台
```

服务间通信：同步走 HTTP（网关 → 引擎 `/internal/**`，与对外 `/api/v1/**` 物理隔离）；异步走 RocketMQ **顺序消息**
——时间线的 `append` / `remove` 共用同一 topic 与同一消费者、靠消息体 `action` 区分，以保证同一帖内先后有序
（拆 tag / 拆消费者会使「已下架内容重新出现」）。未启用 MQ 时自动退回「进程内事件 + HTTP 投递」，克隆即跑。
契约见 `tf-shared`。服务间 `/internal/**` 接口已加 **`X-Internal-Token` 鉴权**（网关与引擎两侧 `InternalTokenInterceptor`，值来自 `TURBOFEED_INTERNAL_TOKEN`）：
**缺令牌时只有 `dev` / `local` 两个 profile 允许 fail-open 放行**（保证「克隆即跑」不受损，启动时打印 banner 告警），
**其余任何 profile（含无 profile 的 default、staging、prod）一律启动即失败**，不允许内部接口裸奔。

## 技术栈

Java 17 · Spring Boot 3.4.3 · Spring Cloud 2024.0.2 + Alibaba 2023.0.3.4（Sentinel 流控） · Maven 多模块 ·
MySQL + ShardingSphere 5.5.3（HASH_MOD，混合：7 表 hashMod4 + 4 表 hashMod128） · Redis 7（Lettuce + 连接池） ·
RocketMQ（rocketmq-spring 2.3.5，顺序消息） · MinIO 8.5.7（对象存储） ·
计数服务预留 Redisson 3.27.2 / Caffeine / H2

## 快速开始

```bash
mvn clean package

# 依赖：MySQL(:3306) / Redis(:6379) / MinIO(:9000，bucket turbo-feed-media 需匿名可读)
# 建表 + 演示账号：deploy/mysql/init-local.sql（既有库升级：deploy/mysql/migrate_post_group.sql）
# 可选：要用 MQ 投递再起 broker —— docker compose up -d

java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar          # 接入层   :8080
java -jar tf-counter/target/tf-counter-0.1.0-SNAPSHOT.jar          # 计数服务 :8081
java -jar tf-feed-engine/target/tf-feed-engine-0.1.0-SNAPSHOT.jar  # Feed引擎 :8083
```

> **容器化一键起（推荐）**：本仓库提供参数化 `Dockerfile`（多阶段构建，按 `build.args.MODULE` 选子模块）+ `docker-compose.yml`（全栈：mysql + redis + minio + 三服务，RocketMQ 收进 `mq` profile）。
> 所有凭据经环境变量注入，仓库默认配置不出现任何字面量：
> ```bash
> cp .env.example .env          # 填 JWT_SECRET / 内部令牌 / MinIO 凭据等；.env 含密钥务必加入 .gitignore
> docker compose up -d          # 不含 MQ（应用默认 mq.enabled=false）
> docker compose --profile mq up -d   # 额外拉起 RocketMQ
> ```
> 详见 `docker-compose.yml` 顶部注释与 `docs/ops/deployment.md`。

环境变量（**部分刻意无默认值，缺失即启动失败**；完整清单见 `.env.example` 与 `docker-compose.yml`）：

| 变量 | 说明 |
|---|---|
| `TURBOFEED_SNOWFLAKE_WORKER_ID` / `TURBOFEED_SNOWFLAKE_DATACENTER_ID` | 雪花实例标识（0~31），单实例本地开发也必须显式给；多实例必须逐实例取不同值，否则同毫秒生成相同 ID 撞主键 |
| `TURBOFEED_JWT_SECRET` | **JWT 签发密钥（必填 ≥32 位）**；缺失则网关启动失败，杜绝 demo 串兜底被生产静默使用 |
| `TURBOFEED_INTERNAL_TOKEN` | 服务间 `/internal/**` 共享令牌；网关与引擎必须两端一致。**仅 `dev`/`local` profile 允许缺失（fail-open + 启动告警），其它 profile 缺失即启动失败** |
| `TURBOFEED_REDIS_PASSWORD` / `TURBOFEED_REDIS_MODE` | Redis 口令（默认空串=无密码）与模式（single / sentinel / cluster） |
| `TURBOFEED_DB_USERNAME` / `TURBOFEED_DB_PASSWORD` | MySQL 账号密码（经 `TURBOFEED_DB_0/1_URL`、`TURBOFEED_REPLICA_DB_0/1_URL` 注入 ShardingSphere 逻辑库数据源） |
| `TURBOFEED_FEED_ENGINE_BASE_URL` / `TURBOFEED_GATEWAY_BASE_URL` | 引擎↔网关互访地址（默认 `http://localhost:8083` / `http://localhost:8080`） |
| `TURBOFEED_MINIO_ENDPOINT` / `ACCESS_KEY` / `SECRET_KEY` / `MEDIA_PUBLIC_URL_BASE` | 对象存储地址 / 凭据 / 公网可读基址（默认 `http://localhost:9000/turbo-feed-media/`） |
| `TURBOFEED_MQ_ENABLED` | 是否启用 RocketMQ 投递（默认 false，克隆即跑） |

常用开关：`--spring.profiles.active=mq` 启用 RocketMQ 投递 · `turbofeed.media.storage=minio|local` 媒体存储实现 ·
`turbofeed.feed.degraded-mode=empty|local-scan` 引擎不可用时的口径（生产必须 `empty`，`local-scan` 会跨分片广播回源）·
`turbofeed.review.moderation-mode=rule|pass|ai` 机审策略 ·
`turbofeed.security.token-blacklist-fail-open-on-error=false` 黑名单查询失败时拒绝（fail-closed，详见下节）。

### Redis 持久化与灾备重建

Redis 在本项目**不只是缓存**，而是多项能力的权威数据源，因此不能按"丢了重建就行"对待：

| Redis 中的数据 | 角色 | 丢失后的表现 |
|---|---|---|
| 令牌黑名单 `turbofeed:jwt:blacklist:{jti}` | 登出 / 风控踢人 / 盗号止损的权威 | 已吊销令牌重新可用，且**无任何报错** |
| 计数 Key `tf:like:count:*` / `tf:comment:like:count:*` | 点赞 / 评论点赞的权威计数源 | 计数归零后从 1 重新累加（冷启动校正也无法覆盖"去重集合已丢"的情形） |
| 去重集合 `tf:like:users:*` / `tf:comment:liked:*` | 幂等权威 | 老内容可被重复点赞 |
| 时间线 `tf:feed:tl:*` / 反查索引 `tf:feed:idx:*` | 公域读模型 | 推荐流空白（需走补投/重建） |
| 会话序列 / 兴趣画像 / 热头 ZSET | 个性化与榜单 | 推荐退化、热榜清空 |

对应措施：

- **compose 演示栈已显式开启 AOF**（`redis:7-alpine` 默认不开）并挂 `redis-data` 数据卷，避免容器删除即全丢。
- **重建入口已可执行**（不是注释承诺）：`/api/v1/admin/counter/*` 提供 recount（按去重集合对账）与 rebuild（按 `media_like` / `comment_like` 灾备表重建）；搜索侧另有 `/api/v1/admin/search/rebuild`。三者均需 `SYSTEM_CONFIG` 权限（仅 ADMIN）。
- 去重集合采用**滑动续期**（详情页读路径刷新 TTL），使仍被浏览的内容不会因固定 8d TTL 到期而丢失幂等语义。

## 技术文档

完整文档树见 [`docs/`](docs/README.md)（与代码同仓，单一文档来源）：

| 入口 | 内容 |
|---|---|
| [docs/architecture/c4-model.md](docs/architecture/c4-model.md) | **C4 架构模型**：上下文 / 容器 / 组件三层（Mermaid 图 + 表格对照） |
| [docs/adr/](docs/adr/README.md) | **架构决策记录**：每份都写清了代价与否决过的方案 |
| [docs/architecture/overview.md](docs/architecture/overview.md) | 系统总体架构：模块 / 通信 / 版本锁定 / 关键决策 |
| [docs/architecture/service-split.md](docs/architecture/service-split.md) | 服务拆分：部署单元 / 端口 / 通信矩阵 |
| [docs/architecture/failure-mode-ledger.md](docs/architecture/failure-mode-ledger.md) | 全链路失败模式对账：投递语义 / 降级口径 / 指标与告警规则 / 排障手册 |
| [docs/modules/](docs/modules/) | 模块级技术文档（与 Maven 模块一一对应，CI 检查） |
| [docs/api/gateway-api.md](docs/api/gateway-api.md) | HTTP 接口：版本前缀与兼容策略 / 认证约定 / 参数 / curl / 错误码 |
| [docs/ops/deployment.md](docs/ops/deployment.md) | 构建部署 / 环境开关 / 生产前必改清单 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献指南：环境 / 构建 / 提交规范 / **提交前自检清单** / 代码约定 |
| [docs/changelog/](docs/changelog/) | 变更记录（每次变更一份 md，编号递增） |

维护约定：代码变更同一提交内同步模块文档，并追加编号 changelog。另附 `TurboFeed-详细设计.html`（上级目录 /Volumes/D/workbuddy/）。
