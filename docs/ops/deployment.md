# 构建与部署

## 1. 构建

```bash
mvn clean package                 # 全模块构建 + repackage 可执行 jar
mvn -pl tf-gateway -am package    # 只构建网关（-am 连带 tf-shared）
```

环境要求：**JDK 17**（与 `maven.compiler.release=17` 一致；IDEA 项目 SDK 也必须为 17，切 JDK 24 会触发 Lombok 注解处理器崩溃）。

## 2. 依赖中间件（MySQL / Redis / MinIO / 可选 RocketMQ）

`docker-compose.yml` **只提供 RocketMQ**（namesrv + broker 5.3.1），Redis / MinIO / MySQL 需自行准备：

```bash
docker compose up -d          # 可选：仅当要启用 MQ 投递时才需要
```

| 组件 | 默认地址 | 用途 |
|---|---|---|
| MySQL | `127.0.0.1:3306` | 元数据（经 ShardingSphere 分片，2 库 × 2 表） |
| Redis | `127.0.0.1:6379` | 限流 / 并发护栏 / 状态缓存 / 公域时间线 |
| MinIO | `127.0.0.1:9000` | 图片对象存储（bucket `turbo-feed-media` 需**匿名可读**，否则返回的图片 URL 浏览器 403） |

建表与种子数据：

```bash
# 新库：建 4 类物理表 + 演示账号（role 列已写入）
mysql < deploy/mysql/init-local.sql

# 既有库升级到「一帖多图」（加 post_id / seq / idx_user_post）
mysql < deploy/mysql/migrate_post_group.sql
```

## 3. 启动

```bash
java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar          # :8080
java -jar tf-counter/target/tf-counter-0.1.0-SNAPSHOT.jar          # :8081（模块骨架）
java -jar tf-feed-engine/target/tf-feed-engine-0.1.0-SNAPSHOT.jar  # :8083
```

**网关的必需环境变量**（缺失会启动失败或连不上依赖）：

| 变量 | 说明 |
|---|---|
| `TURBOFEED_SNOWFLAKE_WORKER_ID` / `TURBOFEED_SNOWFLAKE_DATACENTER_ID` | 雪花实例标识（0~31）。**刻意无默认值：缺失即启动失败**（`SnowflakeConfig#requireInstanceId`），因为 1/1 兜底会让多实例在同一毫秒生成相同 ID、直接撞主键。单实例本地开发也必须显式给一次 |
| `TURBOFEED_REDIS_PASSWORD` | Redis 口令。**已外部化，默认空串 = 无密码**；未注入而 Redis 要求鉴权时直接报 NOAUTH（不静默降级） |
| `TURBOFEED_FEED_ENGINE_BASE_URL` | Feed 引擎地址，默认 `http://localhost:8083` |

示例：

```bash
TURBOFEED_SNOWFLAKE_WORKER_ID=1 TURBOFEED_SNOWFLAKE_DATACENTER_ID=1 \
TURBOFEED_REDIS_PASSWORD=yourpass \
TURBOFEED_FEED_ENGINE_BASE_URL=http://127.0.0.1:8083 \
java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar
```

验收：`curl localhost:808x/actuator/health` 全部 `{"status":"UP"}`；再按 [API 文档](../api/gateway-api.md) 走通「登录 → 上传 → 审核 → 公域可见」。

> 引擎侧 Redis 口令同样走 `TURBOFEED_REDIS_PASSWORD`。

## 4. 环境开关

| 开关 | 默认 | 打开后 |
|---|---|---|
| `turbofeed.mq.enabled` + `--spring.profiles.active=mq` | false | 事件总线与**时间线投递**改走 RocketMQ。**必须同时激活 `mq` profile** 以加载 `application-mq.yml` 的连接配置；只把开关置 true 而不激活 profile，会因缺少 `RocketMQTemplate` Bean 启动失败。MQ 模式下时间线投递为 **fail-fast**（不做降级，靠 `@Transactional` 回滚保证不丢） |
| `turbofeed.media.storage` | `minio` | `local`（落本地磁盘，配合 WebConfig 的 `/media/**` 静态映射）/ `placeholder`（仅拼 URL 不落盘）。三者 `@ConditionalOnProperty` 严格互斥 |
| `turbofeed.media.processing-enabled` | false | ImageIO 缩略图处理链（注意解码内存 ≈ 48MB/张，需评估 QPS 与堆） |
| `turbofeed.feed.degraded-mode` | `empty` | `local-scan` 时引擎不可用回源 `listApprovedGlobal` 广播查全部分片（**仅本地/演示**，生产严禁） |
| `turbofeed.media.review.auto-pass` | false | `true` = 机审结果直接放行（演示占位，跳过人审闸）。**生产严禁** |
| `turbofeed.media.review.moderation-mode` | `rule` | `rule` 本地规则引擎 / `pass` 恒通过占位 / `ai`、`cloud` 预留接入点 |
| `management.health.redis.enabled` | false | Redis 健康指示器（就绪后可开启；保持 false 避免「克隆即跑」时整体状态被拉 DOWN） |

## 5. 生产前必改清单

- [ ] `turbofeed.jwt.secret`：替换为密钥管理注入的高熵值（当前为 demo 字面量，**在仓库里**）
- [ ] `turbofeed.media.minio.access-key / secret-key`：从默认 `minioadmin` 改为 ENV / 密钥管理注入
- [ ] `TURBOFEED_REDIS_PASSWORD` / `TURBOFEED_SNOWFLAKE_*`：由部署清单注入，多实例逐实例取不同雪花值
- [ ] `turbofeed.media.public-url-base`：改指真实对象存储 / CDN 域名
- [ ] `turbofeed.media.review.auto-pass` 确认为 `false`；按需接入云内容安全（`moderation-mode=cloud`）
- [ ] `turbofeed.feed.degraded-mode` 确认为 `empty`（禁止生产回源广播）
- [ ] **服务间鉴权**：`/internal/**` 当前无鉴权，需补服务间令牌 / mTLS
- [ ] **服务注册与发现**：`turbofeed.feed.engine.base-url` 直连 → Nacos + 客户端负载均衡
- [ ] 数据库账号密码：`shardingsphere-config.yaml` 内含开发期凭据，改由密钥管理注入
- [ ] 审核队列与公域流的**广播查询**只适用于演示：`listPendingGlobal` / `listApprovedGlobal` 不带分片键，会路由到全部分片并合并；生产审核队列应走审核中台 + 独立索引

## 6. 部署演进

| 阶段 | 形态 |
|---|---|
| 当前 | 单机三个 jar（counter 为骨架），actuator 探活 |
| Phase 2 | K8s 部署（health 探针 + Prometheus 指标）、集中日志、Micrometer Tracing；服务注册与发现 |
| Phase 3 | 多机房容灾 + 容量规划（tf-benchmark 压测报告驱动） |
