# turbo-feed

短视频 Feed 核心系统 —— 推拉结合 Feed 流 + 分布式计数服务 + 热点治理三件套，自带压测模块与可复现性能报告。

> 状态：微服务结构拆分完成（3 个独立可执行服务）。Feed 读模型与时间线投递已真拆至引擎——网关不再持有任何
> Feed 读模型，只做「接入 + 审核状态机 + 降级决策」；网关侧「上传（一帖多图 1..9 张）→ 审核状态机 → 公域入流」
> 全链路已跑通并有端到端用例覆盖。计数服务、热点治理 SDK、压测模块仍为模块骨架，业务实现按迭代路线逐模块填充。

## 模块结构

```
turbo-feed/
├── tf-shared/         契约库：跨服务传输模型（Result / ErrorCode / FeedItemView / FeedTimelineEvent），零依赖
├── tf-redis/          Redis 接入单一事实源（进程内库）：拓扑装配 单机/哨兵/集群 + 连接池，引入即生效
├── tf-hotspot/        热点治理 SDK（进程内库）：热 Key 探测 + 广播 + Caffeine L1 —— 模块骨架，待实现
├── tf-feed-engine/    Feed 引擎（独立服务 :8083）：公域时间线读模型 —— 多池 ZSET + 反查索引 + 推荐流旁路缓存
│                      + 存量补投消费端（`/internal/feed/timeline/*`），需与 tf-gateway 同时启动
├── tf-counter/        计数服务（独立服务 :8081）：Redis 分桶 + MQ 削峰 + 批量落库 + 三级读缓存(L1/L2/L3) —— 模块骨架，待实现
├── tf-gateway/        HTTP 接入层（独立服务 :8080）：认证/RBAC + 媒体上传(一帖多图) + 审核状态机 + 关注关系(follow/fans) + Sentinel 流量防护
├── tf-benchmark/      压测模块：五场景压测器 + Markdown 报告生成 —— 模块骨架，待实现
└── turbo-feed-ui/     静态前端（纯 HTML，无构建）：登录/注册 + 发布 + 公域 Feed(整帖轮播) + 审核 / 管理工作台
```

服务间通信：同步走 HTTP（网关 → 引擎 `/internal/**`，与对外 `/api/**` 物理隔离）；异步走 RocketMQ **顺序消息**
——时间线的 `append` / `remove` 共用同一 topic 与同一消费者、靠消息体 `action` 区分，以保证同一帖内先后有序
（拆 tag / 拆消费者会使「已下架内容重新出现」）。未启用 MQ 时自动退回「进程内事件 + HTTP 投递」，克隆即跑。
契约见 `tf-shared`。服务间 `/internal/**` 接口已加 **`X-Internal-Token` 鉴权**（fail-open：缺令牌即放行，详见 `docker-compose.yml` 的 `TURBOFEED_INTERNAL_TOKEN`）。

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
| `TURBOFEED_INTERNAL_TOKEN` | 服务间 `/internal/**` 共享令牌；网关与引擎必须两端一致，未配置则两侧 fail-open 放行 |
| `TURBOFEED_REDIS_PASSWORD` / `TURBOFEED_REDIS_MODE` | Redis 口令（默认空串=无密码）与模式（single / sentinel / cluster） |
| `TURBOFEED_DB_USERNAME` / `TURBOFEED_DB_PASSWORD` | MySQL 账号密码（经 `TURBOFEED_DB_0/1_URL`、`TURBOFEED_REPLICA_DB_0/1_URL` 注入 ShardingSphere 逻辑库数据源） |
| `TURBOFEED_FEED_ENGINE_BASE_URL` / `TURBOFEED_GATEWAY_BASE_URL` | 引擎↔网关互访地址（默认 `http://localhost:8083` / `http://localhost:8080`） |
| `TURBOFEED_MINIO_ENDPOINT` / `ACCESS_KEY` / `SECRET_KEY` / `MEDIA_PUBLIC_URL_BASE` | 对象存储地址 / 凭据 / 公网可读基址（默认 `http://localhost:9000/turbo-feed-media/`） |
| `TURBOFEED_MQ_ENABLED` | 是否启用 RocketMQ 投递（默认 false，克隆即跑） |

常用开关：`--spring.profiles.active=mq` 启用 RocketMQ 投递 · `turbofeed.media.storage=minio|local` 媒体存储实现 ·
`turbofeed.feed.degraded-mode=empty|local-scan` 引擎不可用时的口径（生产必须 `empty`，`local-scan` 会跨分片广播回源）·
`turbofeed.review.moderation-mode=rule|pass|ai` 机审策略。

## 技术文档

完整文档树见 [`docs/`](docs/README.md)（与代码同仓，单一文档来源）：

| 入口 | 内容 |
|---|---|
| [docs/architecture/overview.md](docs/architecture/overview.md) | 系统总体架构：模块 / 通信 / 版本锁定 / 关键决策 |
| [docs/architecture/service-split.md](docs/architecture/service-split.md) | 服务拆分：部署单元 / 端口 / 通信矩阵 |
| [docs/modules/](docs/modules/) | 模块级技术文档（与 Maven 模块一一对应） |
| [docs/api/gateway-api.md](docs/api/gateway-api.md) | HTTP 接口：认证约定 / 参数 / curl / 错误码 |
| [docs/ops/deployment.md](docs/ops/deployment.md) | 构建部署 / 环境开关 / 生产前必改清单 |
| [docs/changelog/](docs/changelog/) | 变更记录（每次变更一份 md，编号递增） |

维护约定：代码变更同一提交内同步模块文档，并追加编号 changelog。另附 `TurboFeed-详细设计.html`（上级目录 /Volumes/D/workbuddy/）。
