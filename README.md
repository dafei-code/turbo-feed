# turbo-feed

> **短视频 Feed 后端参考实现 —— 推拉结合 Feed 流 + 分布式计数 + 内容安全词库。**
> Java 17 / Spring Boot 3.4.3 多模块工程，把"上线后才会暴雷"的那些点提前在代码里写好答案。

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-green)
![Maven](https://img.shields.io/badge/Maven-Multi%20Module-blue)
![ShardingSphere](https://img.shields.io/badge/ShardingSphere-5.5.3-red)
![Lines](https://img.shields.io/badge/Java-~55k%20lines-lightgrey)

---

## 为什么值得 Star

这不是又一个"跑通点赞接口"的 Demo。仓库里每个关键取舍都附了实测数据与注释，
而且**不假装没做完的东西已经做完了**——未接通的动作会显式标注当前等价行为。

- 敏感词库热更新会不会卡住线上请求？—— `AtomicReference` 整快照 CAS 切换，**读路径零锁**，后台构建新快照再切换。
- 一段文本同时命中多个敏感词，拦哪个？—— AC 一次扫描收集**全部命中**，逐命中查白名单，再按 `BLOCK(3) > REVIEW(2) > LIMIT(1) > PASS(0)` **取最严重动作**。
- 词库忘了导入、服务却全 200？—— 启动自检 + 运行态指标，`fail-startup-on-empty-dictionary` 可在部署期直接拒绝启动。
- 内部接口 `/internal/**` 裸奔？—— `X-Internal-Token` 双侧校验，**非 dev/local profile 缺令牌启动即失败**。
- MQ 挂了 Feed 断流？—— Outbox + 顺序消息 + 死信清扫 + Prometheus 指标；未启用 MQ 时自动降级进程内投递，克隆即跑。

---

## 模块结构（按真实代码）

```
turbo-feed/
├── tf-gateway/      :8080  接入层（32.8k 行）：认证/RBAC · 媒体上传(一帖多图) · 审核状态机 · 内容安全 · Sentinel
├── tf-feed-engine/   :8083  Feed 引擎（15.8k 行）：多池 ZSET 时间线 + 反查索引 + 推荐流旁路缓存 + 实验分桶
├── tf-counter/       :8081  计数服务（1.4k 行）：Redis 分桶 + MQ 削峰 + 批量落库 + 三级读缓存(L1 Caffeine/L2 Redis/L3 DB)
├── tf-shared/             契约库：跨服务传输模型（Result / ErrorCode / FeedItemView / FeedTimelineEvent），零依赖
├── tf-redis/              Redis 接入单一事实源：单机/哨兵/集群拓扑装配，引入即生效
├── tf-hotspot/            热点治理 SDK：L1 本地缓存 + Redis pub/sub 失效广播（热 Key 滑动窗口探测待补）
├── tf-benchmark/          压测套件（1.8k 行）：LoadRunner + 场景注册表 + CI 退出码 + Markdown 报告
└── turbo-feed-ui/          纯静态前端（无构建）：登录 / 发布 / 公域 Feed / 审核工作台
```

> 行数为各模块 `.java` 实测（含测试代码），随迭代增长。

服务间：同步走 HTTP（对外 `/api/v1/**` 与内部 `/internal/**` 物理隔离）；异步走 RocketMQ **顺序消息**——
时间线 `append`/`remove` 同 topic 同消费者靠 `action` 区分，保证"已下架内容不会复活"。未启用 MQ 时自动退回进程内事件 + HTTP 投递。

## 技术栈

Java 17 · Spring Boot 3.4.3 · Spring Cloud 2024.0.2 + Alibaba 2023.0.3.4（Sentinel 流控）· Maven 多模块 ·
MySQL + ShardingSphere 5.5.3（HASH_MOD，混合：7 表 hashMod4 + 6 表 hashMod128，共 13 张逻辑表）· Redis 7（Lettuce + 连接池）·
RocketMQ（rocketmq-spring 2.3.5，顺序消息）· MinIO 8.5.7（对象存储）· 计数服务预留 Redisson 3.27.2 / Caffeine / H2

---

## 亮点一：一个"教科书级"的内容安全模块

位于 `tf-gateway/.../service/moderation/`，四层解耦：

```
写路径 requireClean(scene, text, userId)
  → ① 长度校验（按场景：昵称/描述/评论）
  → ② TextNormalizer 归一化（抗绕过主战场）
  → ③ AhoCorasick 一次扫描收集全部命中
  → ④ 逐命中白名单豁免 → 按分类取最严重动作
  → ⑤ LongAdder 分场景计数（命中/豁免/各动作）
```

- **Aho-Corasick 多模匹配**：一次扫描命中全词库 O(n+m+z)，`allMatches` 一次拿全。
  代码注释内附 1k/10k/200k/1M 词条的实测吞吐与内存曲线（HashMap 子节点下 1M 词约 590MB、读吞吐达百万级 QPS）。
- **热更新零锁读**：`(COUNT, MAX(updated_at))` 30s 指纹检测 + admin 增删即时 reload；异常保留旧快照（旧词库总比空词库好）。
- **抗绕过归一化流水线**：逐字符 NFKC（折叠全角/兼容字符）→ 剥零宽/方向标记/BOM/软连字符 → 剥分隔符 → 小写 → 繁→简折叠；
  同步维护"归一化下标→原文下标"映射用于前端高亮。
- **分类决策而非二值**：动作分 `PASS / LIMIT / REVIEW / BLOCK`，显式带 `severity()` 定级——
  误杀率与漏放率可分别调，而不是同一个"拦/不拦"旋钮。
  > 诚实标注：当前 `REVIEW` 等价 `BLOCK`、`LIMIT` 等价 `PASS`，尚未接通人审队列与 feed 降权标记。
- **防猜词库**：拒绝时不回显命中词，命中词只进服务端日志与计数器。
- **启动自检**：繁简映射条目编码校验 + 繁→简方向断言，把"手滑错位导致整张表静默失效"挡在部署前。

## 亮点二：可靠性与数据层

- **MySQL + ShardingSphere 5.5.3**：HASH_MOD 混合分片。
- **三级读缓存**：L1 Caffeine → L2 Redis → L3 DB，计数走 Redis 分桶 + MQ 削峰 + 批量落库。
- **Outbox + 死信**：`outbox_dead_letter` 表 + Sweeper 重放 + `tf_outbox_deadletter_total` Prometheus 指标。
- **实验分桶**：userId 稳定映射 100 桶，可对某一桶覆写召回配比做 A/B 对照——推荐迭代第一次有了"尺子"。

---

## 快速开始

```bash
# 依赖：MySQL(:3306) / Redis(:6379) / MinIO(:9000，bucket turbo-feed-media 需匿名可读)
# 建表 + 演示账号：deploy/mysql/init-local.sql（既有库升级：deploy/mysql/migrate_post_group.sql）
cp .env.example .env                       # 填 JWT_SECRET / 内部令牌 / MinIO 凭据

mvn clean package
java -jar tf-gateway/target/tf-gateway-*.jar          # :8080 接入层
java -jar tf-counter/target/tf-counter-*.jar          # :8081 计数
java -jar tf-feed-engine/target/tf-feed-engine-*.jar  # :8083 Feed 引擎
```

> 本地在 APFS 加密卷（如 macOS 挂载的 D 盘）构建时，`mvn package` 的 repackage 阶段可能因 mmap 重命名失败；加 `-Plocal` 跳过 repackage 即可：`mvn -Plocal clean package`。

> **容器化一键起（推荐）**：参数化 `Dockerfile`（多阶段构建，按 `build.args.MODULE` 选子模块）+ `docker-compose.yml`（全栈：mysql + redis + minio + 三服务，RocketMQ 收进 `mq` profile）。
> ```bash
> docker compose up -d                 # 不含 MQ（应用默认 mq.enabled=false）
> docker compose --profile mq up -d    # 额外拉起 RocketMQ
> ```

压测示例（退出码 0 = 成功率 ≥99%，可作 CI 性能门禁）：

```bash
java -cp tf-benchmark/target/classes:<deps> com.turbofeed.benchmark.BenchmarkMain \
  --base-url http://127.0.0.1:8080 --scenario feed \
  --concurrency 32 --duration 60s --warmup 10s \
  --phone 13800138000 --password 123456
```

### 环境变量（部分刻意无默认值，缺失即启动失败）

完整清单见 `.env.example` 与 `docker-compose.yml`：

| 变量 | 说明 |
|---|---|
| `TURBOFEED_SNOWFLAKE_WORKER_ID` / `_DATACENTER_ID` | 雪花实例标识（0~31），单实例也必须显式给，多实例必须逐实例不同 |
| `TURBOFEED_JWT_SECRET` | **JWT 签发密钥（必填 ≥32 位）**；缺失则网关启动失败 |
| `TURBOFEED_INTERNAL_TOKEN` | 服务间 `/internal/**` 共享令牌；仅 `dev`/`local` 允许缺失，其它 profile 缺失即启动失败 |
| `TURBOFEED_REDIS_PASSWORD` / `TURBOFEED_REDIS_MODE` | Redis 口令与模式（single / sentinel / cluster） |
| `TURBOFEED_DB_USERNAME` / `TURBOFEED_DB_PASSWORD` | MySQL 账号密码（经 ShardingSphere 逻辑库数据源注入） |
| `TURBOFEED_MINIO_ENDPOINT` / `ACCESS_KEY` / `SECRET_KEY` | 对象存储地址与凭据 |
| `TURBOFEED_MQ_ENABLED` | 是否启用 RocketMQ 投递（默认 false，克隆即跑） |

常用开关：`--spring.profiles.active=mq` 启用 MQ 投递 · `turbofeed.media.storage=minio|local` ·
`turbofeed.feed.degraded-mode=empty|local-scan` · `turbofeed.review.moderation-mode=rule|pass|ai`。

### Redis 持久化与灾备重建

Redis 在本项目**不只是缓存**，而是多项能力的权威数据源：

| Redis 中的数据 | 角色 | 丢失后的表现 |
|---|---|---|
| 令牌黑名单 `turbofeed:jwt:blacklist:{jti}` | 登出 / 风控踢人的权威 | 已吊销令牌重新可用，且**无任何报错** |
| 计数 Key `tf:like:count:*` | 点赞权威计数源 | 计数归零后从 1 重新累加 |
| 去重集合 `tf:like:users:*` | 幂等权威 | 老内容可被重复点赞 |
| 时间线 `tf:feed:tl:*` / 反查索引 `tf:feed:idx:*` | 公域读模型 | 推荐流空白（需走补投/重建） |

对应措施：compose 演示栈显式开启 AOF 并挂数据卷；`/api/v1/admin/counter/*` 提供 recount/rebuild 重建入口（需 ADMIN）；去重集合滑动续期避免固定 8d TTL 到期丢失幂等。

---

## 技术文档

完整文档树见 [`docs/`](docs/README.md)（与代码同仓，单一来源）：

| 入口 | 内容 |
|---|---|
| [docs/architecture/c4-model.md](docs/architecture/c4-model.md) | C4 架构模型：上下文 / 容器 / 组件三层 |
| [docs/adr/](docs/adr/README.md) | 架构决策记录：每份写清代价与否决过的方案 |
| [docs/architecture/failure-mode-ledger.md](docs/architecture/failure-mode-ledger.md) | 全链路失败模式对账：投递语义 / 降级口径 / 指标与告警 |
| [docs/modules/](docs/modules/) | 模块级技术文档（与 Maven 模块一一对应，CI 检查） |
| [docs/ops/deployment.md](docs/ops/deployment.md) | 构建部署 / 环境开关 / 生产前必改清单 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献指南：环境 / 构建 / 提交规范 / 提交前自检清单 |

---

## 适合谁

- 想看清"一个真实 Feed 后端从 0 到 1 长什么样"的 Java 后端；
- 准备做内容安全 / 分布式计数 / 分库分表，需要一份可跑的参考实现；
- 喜欢"代码里写满为什么这么写"的工程师 —— 这里没有伪装完成的烂尾，只有带数据的取舍与明确标注的待办。

---

## License

以仓库内声明为准。欢迎 Star ⭐，Issue 与 PR 都欢迎。
