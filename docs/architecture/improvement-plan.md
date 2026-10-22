# turbo-feed 架构与技术方案评审（改进路线 v1）

> 范围：仅评审与出方案，
>
> **不改任何项目代码**
>
> 。基于 
>
> `main`
>
>  分支当前快照（commit 
>
> `0e1403f`
>
> ）的实际代码、配置与文档交叉核对。
> 目的：确认现有架构是否站得住、哪些地方是真风险、哪些是 "演示型项目但已逼近生产形态" 需要补的工程化短板。
>
> ⚠️ **本文为评审快照（基于 `0e1403f`），原结论与诊断保持不变；后续改进项落地进展见文末 §7 落地追踪（2026-10-15 更新）。**



***

## 0. 一句话结论

**业务架构设计在同体量开源项目里属于上乘水平**—— 推拉结合时间线、顺序消息保证下架不复活、outbox 至少一次投递、CAS 审核状态机、分片键选择与冷热流量池分级，这些都是抖音 / 小红书真实走过的路，不是纸上谈兵。

**但工程化底线几乎全线缺失**：2.6 万行 Java 代码零测试、无服务间鉴权、无容器化、无 schema 版本管理、可观测性只开了 health/info。当前形态是 "一个设计精良的演示项目"，距离 "能扛流量的生产系统" 差的不是架构，是**测试、部署、可观测、服务治理**这四层基础工程。



***

## 1. 现状确认（以代码为准，纠正文档漂移）

README 与部分架构文档滞后于代码，以下是交叉核对后的真实现状：



| 维度                  | 文档说的                                                | 代码实际                                                                                                           | 差异                   |
| ------------------- | --------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- | -------------------- |
| feed-engine 职责      | "公域时间线读模型"                                          | 已实现完整推荐链路：多路召回（热点 / 兴趣 / 关注 / 向量 HNSW）+ PreRank/Rank（Linear/MultiTarget/DeepFM 占位）+ 多样性重排 + 生态调节 + 冷启动 + 流量池升降 | **文档严重低估**，实际已是推荐服务  |
| user\_phone\_router | sharding.md §5 说 "未启用，用广播 COUNT"                    | `UserPhoneRouterRepository` 存在，`init-local.sql` 注释明确 "已启用，建在 ds\_0 单表"                                         | **文档过时**，注册 / 登录已不广播 |
| media 分片数           | README 说 "2 库 × 2 表"                                | `shardingsphere-config-128.yaml` 已就绪，media 128 物理表，comment/media\_like 已切 128                                  | **配置已切，文档未更新**       |
| 行为日志                | recommendation-design.md 说走 binlog CDC → ClickHouse | `BehaviorLogSink` 写**本地 JSONL 文件**（有界队列、满则丢弃、默认关闭）                                                             | CDC 蓝图未实施，当前是本地落盘    |
| 计数服务 tf-counter     | 模块骨架                                                | 仍为骨架（仅 `CounterApplication`），点赞 / 评论计数实际在 gateway 直写 MySQL                                                     | **未实现**              |
| tf-hotspot          | 模块骨架                                                | **0 个 .java 文件**，纯空模块                                                                                          | **未实现**              |
| tf-benchmark        | 模块骨架                                                | **0 个 .java 文件**                                                                                               | **未实现**              |
| 测试                  | —                                                   | **全仓 0 个测试文件**（`src/test/java` 不存在）                                                                            | **重大缺口**             |
| Prometheus          | overview.md 说 "规划中"                                 | feed-engine pom 已引 `micrometer-registry-prometheus`，但 gateway 未引、yml 未暴露端点                                     | **半成品**              |
| 服务间鉴权               | overview.md 自承 "无，依赖内网信任"                           | `/internal/**` 无任何 token/mTLS 校验                                                                               | **确实裸奔**             |

### 1.1 当前真实架构画像



```
┌─────────────────────────────────────────────────────────────────┐
│  turbo-feed-ui (6 个纯 HTML，无构建)                             │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTPS + JWT
┌────────────────────────────▼────────────────────────────────────┐
│  tf-gateway :8080 (178 个 Java 文件，业务全在这里)               │
│  ├─ AuthController / RegisterController   手机号登录 + RBAC      │
│  ├─ MediaController / presign             上传 + 预签名 + 幂等    │
│  ├─ MediaReviewService (973 行)          审核状态机 + CAS       │
│  ├─ moderation/*                         敏感词 AC + 白名单     │
│  ├─ penalty/* credit/*                   处罚升级 + 信用分级     │
│  ├─ like/comment/report/appeal           互动闭环              │
│  ├─ outbox/*                              事务发件箱 + 中继     │
│  ├─ Sentinel 限流 + Redis 状态缓存广播                            │
│  └─ → ShardingSphere → MySQL (user 4表 / media 128表 / ...)   │
└──────┬──────────────────────────┬───────────────────┬─────────┘
       │ 同步 HTTP /internal/**    │ RocketMQ 顺序消息  │ MinIO
       ▼                          │ append/remove      ▼
┌──────────────────────────────┐  ▼  ┌──────────────────────────┐
│  tf-feed-engine :8083      │◄────│  RocketMQ Broker         │
│  (55 个 Java 文件)           │     │  (docker-compose 本地起) │
│  ├─ recall/*  4 路召回       │     └──────────────────────────┘
│  ├─ ranking/* 排序模型族     │
│  ├─ timeline/FeedTimelineStore (1330 行上帝类)                 │
│  ├─ interest/* social/*     │
│  └─ BehaviorLogSink → 本地 JSONL                              │
└──────┬───────────────────────┘
       │ Lettuce
       ▼
┌──────────────────────────────┐
│  Redis 7/8 (single/cluster/  │
│  sentinel 三态由 tf-redis 装配)│
└──────────────────────────────┘

  tf-counter :8081  → 空壳（仅启动类）
  tf-hotspot        → 空模块（0 文件）
  tf-benchmark      → 空模块（0 文件）
```



***

## 2. 做得好的设计（不要动，保持）

这些决策经得起推敲，是项目的核心价值：



1. **顺序消息保序**：append/remove 共用 topic + `ConsumeMode.ORDERLY`，拒绝按 action 拆 tag—— 避免 "已下架内容复活" 的内容安全事故。这是踩过坑才会有的直觉。

2. **outbox + 至少一次投递**：事件行与业务数据同事务落库，提交后直投 + 中继补偿重投，不依赖 MQ 事务消息。消费端幂等。

3. **审核 CAS 状态机**：`AND status=期望前置态` 消除 TOCTOU，有界重试吸收 InnoDB 死锁。

4. **降级默认不回源**：`degraded-mode=empty`，引擎故障时拒绝跨分片广播 —— 把单次依赖故障挡住，不引爆存储层。

5. **密钥 / 实例标识无默认值**：JWT secret、雪花 worker-id 缺失即启动失败，不允许生产用 demo 默认值静默跑起来。

6. **分片键选择**：user 按 id、media 按 user\_id、comment/report 按 media\_id，高频路径全部单分片命中。

7. **时间线写时物化**：公域读走 Redis ZSET，不扫分片库；读模型与缓存同域（缓存随引擎拆走）。

8. **抽象端口 + 多实现**：`MediaStorageClient`（minio/local/placeholder）、`FeedTimelinePublisher`（HTTP/RocketMQ）、`ContentModeration`（rule/pass/ai/cloud）都靠 `@ConditionalOnProperty` 互斥装配，业务代码零改动切换。

9. **行为埋点 fail-open**：队列满直接丢弃计数，不阻塞主链路 ——"丢样本不丢可用性" 的取舍明确。



***

## 3. 问题诊断（按优先级）

### P0 — 不补就不敢上生产

#### P0-1 零自动化测试，2.6 万行代码全靠人肉回归



* **现象**：全仓 `find -path "*/src/test/*" -name "*.java"` 返回 0。`pom.xml` 没有 `spring-boot-starter-test` 依赖。

* **影响**：


  * 审核状态机的 6 态流转、CAS 并发重试、顺序消费 append/remove、outbox 中继补偿、分片路由 —— 这些 "靠 changelog 踩坑记录" 保证正确性的逻辑，任何一次重构都可能悄悄回归。

  * 67 个 changelog 记录了大量 "修复顺序消息失序"" 修复死锁 ""修复跨分片广播" 类问题，但修复本身没有测试锁定，下次改回来还会犯。

* **建议方向**（不展开写代码，只定策略）：

1. **第一优先**：给 `MediaReviewService`（审核状态机）、`FeedTimelineStore` 的 append/remove/promote 路径、`UserJdbcRepository` 的分片路由、outbox 中继补偿补单元测试 + 集成测试。

2. 引入 Testcontainers 起 MySQL + Redis 做真集成测试，不要用 H2 mock ShardingSphere（SS 在 H2 上行为与 MySQL 不一致）。

3. CI 门禁：`mvn test` 必须过才允许合并。

4. 覆盖率门槛先定线：核心包（service/review、feedengine/timeline、outbox）≥ 60%，逐步提。

#### P0-2 /internal/\*\* 无鉴权，服务间信任裸奔



* **现象**：`FeedTimelineController`、`PostStatController` 挂 `/internal/**`，任何能访问 8083 端口的网络调用都能 `POST /internal/feed/timeline/append` 或 `remove`，直接篡改公域时间线。

* **影响**：一旦网关与引擎之间的网络策略收紧失败（或被横向穿透），攻击者可任意下架 / 上架内容。

* **建议方向**：

1. 短期：网关心头加 `X-Internal-Token` 头（配置注入），引擎侧拦截器校验。

2. 中期：换 mTLS 或服务网格 SPIFFE 身份。

3. `/internal/**` 在部署层用 NetworkPolicy / 安全组限制**只允许网关 Pod 访问**。

#### P0-3 无 schema 版本管理，DDL 靠手写 SQL 脚本



* **现象**：`deploy/mysql/init-local.sql` 600KB+，顶部 `DROP DATABASE` 重建；演进靠手工 `migrate_post_group.sql`、`alter_media_tag.sql`、`alter_media_duration.sql`。没有 Flyway/Liquibase。

* **影响**：


  * 新环境部署要按正确顺序串多个 alter 脚本，漏一个就启动报 `TableNotFoundException`。

  * 生产绝对不能跑 `DROP DATABASE`，但本地脚本与生产脚本没有隔离。

  * 回滚无据可查。

* **建议方向**：

1. 引入 **Flyway**（Spring Boot 原生集成，零配置起步）。

2. 把现有 DDL 按逻辑拆成 `V1__init.sql`、`V2__add_post_group.sql`、`V3__add_media_tag.sql`...，版本化管理。

3. `init-local.sql` 的 DROP 行为只保留在本地 profile，生产 Flyway migrate 永不 DROP。

4. 分片表 DDL 仍需手工生成（128 张 media 表），但用 SQL 模板脚本批量生成，纳入 Flyway 管理。

#### P0-4 可观测性半成品：无 metrics、无 tracing、日志不集中



* **现象**：


  * feed-engine pom 引了 `micrometer-registry-prometheus`，但 `application.yml` 的 `management.endpoints.web.exposure.include` 只开了 `health,info`，没开 `prometheus`。gateway pom 根本没引 micrometer。

  * 无 traceId 贯穿（gateway → feed-engine 同步 HTTP 调用没有 trace 头透传）。

  * 日志全部打到本地 stdout，无集中收集；`BehaviorLogSink` 写本地 JSONL 文件，Pod 重建即丢。

* **影响**：线上出问题无法定位是网关慢、引擎慢、还是 Redis/MySQL 慢；推荐效果问题无法回溯单用户曝光序列。

* **建议方向**：

1. gateway 补引 `micrometer-registry-prometheus`，两个服务都开 `management.endpoints.web.exposure.include: health,info,prometheus,metrics`。

2. 同步引入 `micrometer-tracing-bridge-otel` + zipkin/OTLP，traceId 经 RestClient 透传到 feed-engine。

3. 日志 JSON 结构化输出（logback-encoder），字段带 traceId、userId、mediaId。

4. `BehaviorLogSink` 增加 "双写" 选项：本地文件 + 异步传 Kafka / 对象存储，Pod 重启不丢训练样本。

#### P0-5 无服务发现与配置中心，多实例扩不动



* **现象**：网关直连 `turbofeed.feed.engine.base-url=http://localhost:8083`；Sentinel 规则只在本地 JSON 文件；雪花 worker-id 靠环境变量手填。

* **影响**：


  * 引擎扩容到 2 个实例，网关只能配一个地址，另一个不接流量；或配负载均衡但健康检查、摘除都没有。

  * Sentinel 限流规则要改就得改本地文件重启服务，无法动态推送。

  * 多实例雪花 worker-id 要人肉分配，撞号风险高。

* **建议方向**：

1. 引入 **Nacos**（项目已引 spring-cloud-alibaba，Sentinel 与 Nacos 天然集成，成本最低）。

2. `FeedEngineClient` 从直连改为 `@LoadBalanced` RestClient + Nacos 服务发现。

3. Sentinel 规则数据源切 Nacos，动态推送。

4. 雪花 worker-id 可由 Nacos 实例注册表自动分配，或直接换成数据库号段模式。



***

### P1 — 架构债与功能缺口

#### P1-1 FeedTimelineStore 上帝类（1330 行）



* **现象**：一个类承担了：时间线 ZSET 读写、反查索引、流量池 promote/demote、推荐打分 `scoreOf`、冷启动候选收集、多样性重排、槽位分配 `allocateSlots`、审核拦截键收集。

* **影响**：改推荐策略要动时间线存储，改下架逻辑可能影响排序；无法独立测试每一路召回 / 重排。

* **建议方向**：按职责拆成


  * `TimelineZSetStore`（纯 Redis 读写）

  * `FeedRanker`（打分 + 槽位分配）

  * `ColdStartService`（已存在，从 Store 里抽干净）

  * `PoolTransitionService`（promote/demote/detach）

  * `ModerationGate`（审核拦截键）

  * 保留 `FeedTimelineStore` 作为门面，编排上述组件。

#### P1-2 tf-counter 已落地为「计数收敛底座」（原「热点计数直写 MySQL」结论已过时）

* **原描述的偏差**：原文称「点赞 / 评论在 gateway 直接 `UPDATE media SET like_count=like_count+1`，高并发下热点行锁竞争」。复核代码后该前提**已不成立**——网关 `MediaLikeService` 现走 Redis 64 槽 Set + `SCARD` 聚合去重计数，`media` 表亦**无 `like_count` 列**（全仓零引用），不存在热点行锁。ADR-003 的原始动因随之消失。

* **本次落地形态（收敛底座，非替代）**：新增独立部署单元 `tf-counter`（默认 8081），只补网关实时计数**不覆盖**的持久侧能力：
  * 写路径：点赞状态变化时发一条「增量」事件（`postId + 类型 + ±1`）→ 内存缓冲 → 定时批量落 `post_counter`（最终一致）。只带增量、不带绝对值，与 Redis 权威计数彻底解耦，绝不抢写路径上的实时计数；
  * 读路径：三级读缓存 Caffeine L1 → Redis L2(opt-in) → DB L3，面向管理端 / 批量计数，不替代卡片红心的实时计数；
  * 传输：HTTP 兜底为主 + RocketMQ 顺序键 `postId`(opt-in)，二者按 `turbofeed.mq.enabled` 互斥，任意部署下只存在一套发布器；
  * 零回归：`turbofeed.counter.service.enabled=false`（默认）时网关侧发布器 Bean 不创建，`ObjectProvider.ifAvailable` 为空实现，点赞主链路完全不受影响。

* **验证**：三个相关模块 `mvn compile` 全绿；tf-counter 真启动（H2）e2e——批量 incr → 定时落库 → 查询 `likeCount=1→2 / commentCount=1` 聚合正确，未接触过的 key 返回全零且不报错；网关侧 `counter.service.enabled=true` 真启动确认**仅创建一个** HTTP 发布器 Bean（无 MQ 分支歧义）。完整「点赞→计数」联调待本机 Redis 起来后补：网关 `accountStateCacheInvalidationContainer` 在启动期即连 Redis。

#### P1-3 无容器化部署



* **现象**：没有 Dockerfile，没有 K8s manifest，本地靠 `java -jar`。docker-compose 只起了 RocketMQ。

* **建议方向**：

1. 每个可执行服务一个 Dockerfile（多阶段构建：Maven 构建 → JRE 运行）。

2. 补 K8s Deployment/Service/ConfigMap/Secret（Nacos 注册后用 Service 发现）。

3. 本地补一个 `docker-compose.yml` 一键起全栈（MySQL+Redis+RocketMQ + 三个服务），降低新人上手成本。

#### P1-4 文档与代码漂移需一次性对齐



* **现象**：§1 表格列了 9 处漂移。新同学读 README 会以为 feed-engine 只有时间线，实际已有完整推荐；以为手机号还在广播，实际已路由表。

* **建议方向**：

1. 一次性更新 README、overview.md、service-split.md、sharding.md，与代码对齐。

2. 建立纪律：代码变更 PR 必须同步改文档（当前 changelog 纪律已有，但模块文档没跟上）。

#### P1-5 前端纯静态 HTML，无工程化



* **现象**：`turbo-feed-ui/` 下 6 个 HTML 文件，内联 CSS/JS，无构建、无路由、无状态管理。

* **建议方向**：


  * 如果定位是 "架构演示项目"，保持现状即可，但建议至少用原生 ES Module 拆文件、加个简单的 fetch 封装。

  * 如果要往真实产品走，换 Vite + Vue/React，但这会显著增加复杂度，建议放到 P2 再议。



***

### P2 — 演进优化（不紧急，但值得记下来）



| #    | 问题                      | 建议                                                                                                                              |
| ---- | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| P2-1 | 无 A/B 实验框架              | 推荐多路召回 / 排序权重切换后，效果无法归因。引入分桶实验框架（按 userId hash 分桶），网关透传 `X-Experiment-Bucket` 头，曝光日志带 bucket。                                   |
| P2-2 | 向量召回依赖 Redis Stack HNSW | 生产级向量检索建议评估独立向量库（Qdrant/Milvus），当前 Redis HNSW 适合演示，亿级向量不够。                                                                      |
| P2-3 | 无 API 版本前缀              | `/api/**` 建议加 `/api/v1/**`，未来不破坏老客户端。                                                                                           |
| P2-4 | DeepFM 排序模型是空壳          | `DeepFmRankingModel` 当前是占位。训练样本走 BehaviorLogSink JSONL，真正要训模型需要 Python 侧离线训练 + 在线模型服务（TF Serving / ONNX Runtime）。建议放到推荐链路 P3 期。 |
| P2-5 | 无 HTTPS/CDN/WAF         | 生产必须：网关前加 Nginx/Ingress 终止 TLS，媒体走 CDN，上传接口加 WAF。                                                                               |
| P2-6 | tf-hotspot 热 Key L1 未实现 | 当前 gateway 的 AccountStateCache 已经是 "本地缓存 + pub/sub 失效广播" 模式，可以把这套模式抽成 tf-hotspot SDK 复用。                                        |
| P2-7 | 多机房 / 单元化               | 远期，当前阶段不做。                                                                                                                      |



***

## 4. 分期改进路线

### 阶段 A：工程化底线（预计 2\~3 周，不碰业务逻辑）

目标：让这个项目 "敢给别人看、敢自己改"。



1. 补测试：核心包单测 + Testcontainers 集成测试，CI 门禁。

2. 引入 Flyway，schema 版本化。

3. 补 Prometheus 端点（gateway + feed-engine）+ Micrometer Tracing。

4. 补 Dockerfile + K8s manifest + 本地一键 docker-compose。

5. `/internal/**` 加 Internal Token 拦截器。

6. 一次性对齐所有文档与代码。

**验收**：



* `mvn test` 在干净环境跑通；

* `docker compose up` 一键起全栈；

* 访问 `:8080/actuator/prometheus` 和 `:8083/actuator/prometheus` 有 metrics；

* 不带 Internal Token 访问 `:8083/internal/feed/timeline/append` 返回 401。

### 阶段 B：服务治理与功能补全（预计 3\~4 周）

目标：多实例可扩、计数不抖、热点可控。



1. 接入 Nacos：服务发现 + Sentinel 规则动态推送 + 配置中心。

2. 落地 tf-counter：Redis 分桶 + MQ 削峰 + 批量落库，点赞 / 评论计数改走三级缓存。

3. 抽 tf-hotspot：把 AccountStateCache 的 L1 + 广播模式抽象成 SDK，复用到时间线热 Key。

4. 拆 FeedTimelineStore 上帝类（§P1-1）。

5. BehaviorLogSink 双写 Kafka / 对象存储。

**验收**：



* 网关 / 引擎扩容到 2 实例，Nacos 自动注册摘除；

* 点赞接口压测 1000 QPS 下 MySQL 无热点行锁（看慢查询日志）；

* 推荐曝光日志带 traceId + experiment bucket。

### 阶段 C：推荐体系生产化（按 recommendation-design.md 的 P1\~P3 走）

目标：个性化推荐可度量、可迭代。



1. A/B 实验框架。

2. 行为日志出仓：Canal/CDC → ClickHouse 明细层。

3. 离线画像 T+1 → Redis 在线特征。

4. 排序从规则模型过渡到 LR/GBDT，DeepFM 占位保留。

5. 向量召回评估独立向量库。

**验收**：



* 能回答 "个性化流 vs 热门流，曝光→点赞转化率差异"；

* 任意用户可查 top-5 品类偏好；

* 模型排序相对规则排序有可量化提升。



***

## 5. 明确不做的事



1. **不推翻现有分片结构**——media 128 表、user 4 表的设计是对的，不为推荐加索引 / 宽表，分析负载出仓。

2. **不上 Flink/K8s 多机房**—— 当前阶段单机 CH + 已有 MQ 足够，等真实瓶颈出现再上。

3. **不换 RPC 框架**—— 同步调用面只有 1 个读接口，HTTP 足够；gRPC/Dubbo 解决不了引擎抖动导致的写失败（那是 MQ 的事）。

4. **不重写前端**—— 保持静态 HTML，除非明确要做产品。

5. **不在 P0 阶段改业务逻辑**—— 测试、部署、可观测是底线，业务架构当前是好的，先不动。



***

## 6. 附：评审依据



* 代码快照：`main` @ `0e1403f`

* 关键文件：


  * `pom.xml`（依赖版本锁定）

  * `docs/architecture/overview.md`、`service-split.md`、`sharding.md`、`recommendation-design.md`

  * `tf-gateway/src/main/resources/application.yml`、`shardingsphere-config-128.yaml`

  * `tf-feed-engine/src/main/java/.../timeline/FeedTimelineStore.java`（1330 行）

  * `tf-gateway/src/main/java/.../service/review/MediaReviewService.java`（973 行）

  * `deploy/mysql/init-local.sql`

* 统计：全仓 Java 文件 242 个，总行数 25985（main 源码），测试文件 0。

***

## 7. 改进落地追踪（2026-10-15 更新）

> 本节追踪 §3 问题诊断各改进项的实施进展。**评审快照（基于 `0e1403f`）的原结论与诊断保持不变**，仅补充后续落地状态，避免读者被快照结论误导。
> 全部已落地项均经真启动 / e2e 验证；截至本更新仍未 push（按项目纪律先验证再推送，推送需显式确认）。

| 项 | 状态 | 落地说明 |
| --- | --- | --- |
| P0-2 `/internal/**` 服务间鉴权 | ✅ 已落地（#186） | 网关 + 引擎双端 `InternalTokenInterceptor` 校验 `X-Internal-Token`；值来自 `TURBOFEED_INTERNAL_TOKEN`，未配置即 fail-open 放行（克隆即跑），本地 yml 写死 `local-dev-internal-token` 开启校验。e2e 三态（无 / 错 / 正确 token）全绿 |
| P0-3 Flyway schema 版本管理 | ✅ 已落地 | 仅版本化「单表」(SINGLE 登记的 5 张：sensitive_word/sensitive_whitelist/user_phone_router/outbox_event/review_task)；分片表(540 张)仍走 init-local.sql 模板。Spring Boot 3.4.x 无 spring-boot-starter-flyway(仅 4.x 起)，改用 BOM 托管 flyway-core(10.20.1)+flyway-mysql；默认关闭(TURBOFEED_FLYWAY_ENABLED)，直连物理库 turbo_feed_1 绕过 ShardingSphere。编译通过 + 直连 DB 只读 info 校验(V1 被发现、MySQL 方言加载) 全绿 |
| P0-4 可观测性 | ✅ 已落地（metrics） | 两服务均引 `micrometer-registry-prometheus` 且暴露 `health,info,prometheus,metrics` 端点：gateway 补依赖 + 两服务补 `metrics` 端点（对齐 §3 P0-4 建议原文）；`/actuator/prometheus` 可被 Grafana 拉取。tracing（micrometer-tracing-bridge-otel + zipkin/OTLP）因需采集后端（zipkin/OTLP Collector），未擅自加依赖，列为阶段B 演进项 |
| P0-1 自动化测试 | 🔧 进行中（2026-10-17） | ADR-001 已落（`docs/architecture/adr-001-testing.md`）；4 靶心全就位——①审核 CAS 状态机 `MediaReviewServiceTest`(4) ②时间线 append-remove-promote `FeedTimelineStoreTest`(4) ③outbox 中继 `OutboxRelayTest`(4) ④用户分片路由 `UserJdbcRepositoryRoutingTest`(Testcontainers 真 MySQL，@RequiresDocker 守卫，本地无 Docker 跳过、CI 有 Docker 跑全量)。**本地 `mvn test` 已全绿**：网关 8/8 + 引擎 4/4，集成测因无 Docker 被 skip；CI 门禁 `.github/workflows/ci.yml`(`mvn -B clean test`，ubuntu 自带 Docker) 已加；根 pom 显式带 surefire 3.2.5 + testcontainers-bom。集成测的「真 MySQL 跑通」需 CI/Docker 环境确认（本地沙箱无 Docker，无法执行验证） |
| P0-5 服务发现 / 配置中心 | 🟡 部分（2026-10-19 第一批已落） | 服务发现(双向 opt-in)+Sentinel 规则 Nacos 数据源(opt-in) 已落：依赖 + `@LoadBalanced RestClient` + `service-name` 切换 + 本地规则让位；`mvn compile` 通过、默认配置(Nacos 全关)真启动零回归。worker-id 集中分配经评审回退 phase-2（雪花 env-var 硬失败已保不撞号，短期够用）。真链路(多实例注册/LB/故障摘除、Sentinel 规则热推)待有 Nacos 的环境确认 |
| P1-2 tf-counter 计数服务 | ✅ 已落地（收敛底座）+ 🔧 分布式增强 | 新增独立部署单元 `tf-counter`：点赞/评论/分享「增量事件」→ 缓冲 → 定时批量落 `post_counter`（最终一致）；三级读缓存 Caffeine L1/Redis L2(opt-in)/DB L3；传输 HTTP 兜底 + RocketMQ 顺序键 `postId`(opt-in) 互斥。默认关闭零回归。→ **分布式增强（2026-10-19，编译+单测全绿、未 push）**：抽 `CounterBuffer` 接口（内存默认 / 共享 Redis 双实现）；`CounterFlushService` 批量 UPSERT + `batch_id` 去重（`counter_flush_dedup`）实现 flush 幂等；`StandaloneCounterFlushScheduler`（默认无锁）/ `ClusteredCounterFlushScheduler`（ShedLock 选主独占 drain，配对共享 Redis 缓冲，多副本不丢不重）；新增 H2 集成测试（`applyBatch` 同 batch_id 重放计数不变、不同 batch_id 各落、ShedLock JDBC 锁在 H2 可用）全绿。`mvn compile`+`mvn test` 通过；Redis 真链路（共享缓冲 drain + ShedLock 选主）需本机 Redis 起后才能 e2e。详见 ADR-003 §6 |
| P1-3 容器化部署 | ✅ 已落地（#187） | 参数化 `Dockerfile`（多阶段，按 `build.args.MODULE` 选子模块）+ 全栈 `docker-compose.yml`（mysql/redis/minio/三服务，RocketMQ 收进 `mq` profile）+ `.env.example`；所有凭据经环境变量注入，`docker compose config` 通过 |
| P1-4 文档与代码漂移对齐 | ✅ 已落地（#188） | README / overview.md / service-split.md / sharding.md 已对齐代码实际（11 张逻辑表、关注关系、X-Internal-Token、容器化）；本文 §1 表格中「配置已切，文档未更新」「确实裸奔」等已随 #188/#186 失效，以本节状态为准 |
| 关注关系（follow/fans，P0-2 业务） | ✅ 已落地 | `FollowService`/`SocialController` + `user_follow`/`user_fans`（hashMod128）双写 + Redis ZSET `tf:follow:{uid}` 读源，G6 关注流已接入；属产品功能，非评审原始项 |
| P2-6 tf-hotspot 热 Key L1 SDK | ✅ 已落地（SDK + AccountStateCache 重构 + 第二消费者） | 抽 `LocalCacheWithBroadcast<K,V>` + `CacheInvalidationPublisher` 契约到 tf-hotspot（零 Spring/Redis 依赖），封装「本地缓存+pub/sub 失效广播+version stamp 防脏窗口+自动退化」；`AccountStateCache` 重构为薄装配层（level/write 两路委托），公共 API 与 `INVALIDATE_CHANNEL`/订阅不变、行为零变更；第二消费者 `HotTimelineKeyCache`（引擎热点榜 L1，TTL 失效、广播关闭，因写源为行为事件）已落地；配套 8+4 单测全绿（见 ADR-004） |

**待办（按优先级）**：P0-1 测试🔧（4 靶心单测/集成测全就位，本地 12/12 绿、集成测真 MySQL 跑通待 CI/Docker 确认）、P0-5 Nacos🔧（服务发现+Sentinel→Nacos 已落、worker-id phase-2；真链路待 Nacos 环境验证）、P1-1 FeedTimelineStore 拆分、P1-5 前端工程化；P2-6 SDK 已落地（见上），时间线热 Key 复用待排期，P2 其余（A/B、向量库、HTTPS/CDN、API 版本前缀）为演进优化。