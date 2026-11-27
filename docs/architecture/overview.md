# 系统总体架构

> turbo-feed：短视频 Feed 核心系统——推拉结合 Feed 流 + 分布式计数 + 热点治理，  
> UGC 图文上传（一帖多图，审核后展示）作为网关侧首个完整业务闭环。  
> **当前状态**：微服务结构已拆分（3 个可执行服务 + 静态前端）。网关业务闭环完整；Feed 引擎已是**推荐漏斗 + 公域时间线读模型**（多路召回 / 粗排 / 精排 / 三层重排，全链路 fail-open）；计数服务、热点 SDK、压测模块均已落地。Nacos 服务发现与 Sentinel 规则热推为 opt-in；outbox CDC 已端到端验证但默认关闭，ClickHouse 出仓与 T+1 画像仍在规划。

## 1. 系统形态：3 个独立服务 + 1 个静态前端

```mermaid
flowchart LR
    subgraph clients[客户端]
        UI[turbo-feed-ui<br/>静态前端：login / register / user / feed / review / admin]
    end

    subgraph services[独立部署的 Spring Boot 服务]
        GW[tf-gateway :8080<br/>接入：认证+RBAC / 图文上传 / 审核 / 评论 / 关注 / 搜索 / 收藏 / 通知]
        FE[tf-feed-engine :8083<br/>推荐漏斗 + 公域时间线读模型]
        CT[tf-counter :8081<br/>Redis 分桶 + MQ 削峰 + 三级读缓存]
    end

    subgraph infra[基础设施]
        DB[(MySQL<br/>ShardingSphere 16 分片逻辑表 + 12 单表)]
        RD[(Redis 7)]
        MQ[[RocketMQ<br/>可选：mq profile]]
        CDC[[Canal<br/>outbox CDC opt-in]]
        OS[(MinIO<br/>对象存储 + 行为日志归档)]
    end

    subgraph libs[进程内库（jar 依赖）]
        SH[tf-shared<br/>Result / ErrorCode / 契约]
    end

    UI -->|HTTP + JWT| GW
    GW -->|同步 HTTP：推荐流读| FE
    GW -->|异步 MQ 顺序消息：入流/下架| MQ
    MQ --> FE
    DB -.->|binlog| CDC
    CDC -->|eventId + traceId| MQ
    GW --> DB
    GW & FE --> RD
    GW --> OS
    GW & FE --> SH
```

| 模块               | 形态       | 端口   | 当前职责                                                                 | 实现状态             |
| ---------------- | -------- | ---- | -------------------------------------------------------------------- | ---------------- |
| `tf-gateway`     | 可执行服务    | 8080 | 登录注册 + RBAC、图文上传（一帖多图）、审核/举报/申诉、评论/点赞/收藏/关注/搜索/通知、Outbox 与 CDC 投递 | ✅ 完整 |
| `tf-feed-engine` | 可执行服务    | 8083 | 推荐漏斗（多路召回 / 粗排 / 精排 / 三层重排）、公域时间线读模型、关注流读（大 V 优先扫描 + 作者索引分片 + L1）、`/internal/**`、MQ 顺序消费 | ✅ 已实现；传统扇出蓝图未实现 |
| `tf-counter`     | 可执行服务    | 8081 | Redis 分桶缓冲 + MQ 削峰 + 批量落库 + 三级读缓存 + ShedLock 多副本         | ✅ 已实现             |
| `tf-hotspot`     | 进程内 SDK  | —    | `LocalCacheWithBroadcast`：Caffeine L1 + Redis pub/sub 失效广播 + 版本戳       | ✅ SDK 已落地；冷热桶迁移待做 |
| `tf-benchmark`   | 压测模块     | —    | 五场景 HTTP 压测器 + 预热 + 分位统计 + Markdown 报告（不入服务拓扑）            | ✅ 已实现             |
| `tf-shared`      | 纯 POJO 库 | —    | `Result` / `ErrorCode` / `FeedItemView` / `FeedTimelineEvent`，零第三方依赖 | ✅                |
| `turbo-feed-ui`  | 静态前端     | —    | 纯 HTML（无构建）：登录 / 注册 / 发布 / 公域 Feed 轮播 / 审核与管理工作台                     | ✅                |

> 服务拆分动机、通信矩阵与演进路线详见 [service-split.md](service-split.md)；分片设计见 [sharding.md](sharding.md)。

## 2. 技术栈与版本锁定

| 组件                   | 版本         | 说明                                                                            |
| -------------------- | ---------- | ----------------------------------------------------------------------------- |
| Java                 | 17         | `maven.compiler.release=17`，IDEA 项目 SDK 必须同为 17                               |
| Spring Boot          | 3.4.3      | BOM 统一托管依赖版本（caffeine / h2 / mysql-connector-j / lombok 均由其托管）                |
| Spring Cloud         | 2024.0.2   | 必须由父 POM 显式 import `spring-cloud-dependencies` 锁定                             |
| Spring Cloud Alibaba | 2023.0.3.4 | Sentinel 流量防护；版本不可从命名反推 Spring Cloud 版本，看其 POM parent（4.1.0 → SC 2024.0.x）    |
| RocketMQ starter     | **2.3.5**  | 事件总线 + 时间线顺序消息；`turbofeed.mq.enabled` + `mq` profile 条件启用                     |
| Canal client         | **1.1.7**  | `outbox_event` CDC 旁路：binlog → Canal → 转发器 → MQ → `OutboxCdcConsumer`，默认关闭 |
| ShardingSphere JDBC  | **5.5.3**  | 声明在 `tf-gateway/pom.xml`；16 张逻辑表分片（7×hashMod4 + 9×hashMod128）+ 12 张单表，见 sharding.md |
| MinIO SDK            | **8.5.7**  | 对象存储（`turbofeed.media.storage=minio`，当前默认）                                    |
| Redisson             | 3.27.2     | **仅声明在 tf-counter**（尚未实现）；网关与引擎均用官方 `spring-boot-starter-data-redis`（Lettuce） |
| Caffeine / H2        | BOM 托管     | **仅 tf-counter 声明**，当前未被实际使用                                                  |
| Lombok               | BOM 托管     | 构造器注入 / `@Slf4j`                                                              |

版本组合教训（详见 changelog）：SC 与 Boot 的 release train 对应关系必须查官方矩阵，SCA 版本命名不能反推 Spring Cloud 版本。

## 3. 关键架构决策

| 决策     | 选择                                                         | 理由                                                                                     |
| ------ | ---------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| 身份传递   | JWT + ThreadLocal 上下文                                      | userId 不进方法签名，客户端无法伪造 / 越权；`UserContextHolder.requireUserId()` 在业务层显式声明受保护语义           |
| 授权模型   | RBAC：角色→权限集 + `@RequirePermission`                         | 接口按**权限**校验而非按角色名，新增角色只加枚举 + 一行映射，业务零侵入。角色由 `user` 表 `role` 列派生，配置里不存白名单               |
| 内容粒度   | **一帖多图**：一次上传 = 一个 `post_id`，`seq` 定序                      | 分片键仍是 `user_id` → 同帖必落同一分片；历史单图数据 `post_id=''` 回退 `media_id`，零迁移                       |
| 审核粒度   | **整帖一审** + 6 状态机                                           | 一帖 N 图同上同下；允许「一帖内一半可见」会让同一内容在不同入口呈现矛盾可见性                                               |
| 状态流转并发 | **CAS（`AND status=期望前置态`）+ 有界重试**                          | 消除检查-后-执行的 TOCTOU；整帖多行 UPDATE 会在二级索引与聚簇主键间形成加锁次序反转 → InnoDB 死锁，靠重试吸收（见 changelog 0023） |
| 公域读    | 写时物化时间线（Redis ZSET）+ 独立引擎                                  | 消灭「跨分片广播扫全表」；读路径 O(log n)、零扫分片库                                                        |
| 事件总线   | 端口 + Local / RocketMQ 双实现                                  | 默认本地事件保证「克隆即跑」，`turbofeed.mq.enabled=true` 平滑切跨进程，业务代码零改动 |
| Outbox 规模化 | SENT 清理 + `outbox_event` 128 分片 + CDC 旁路              | 终态行不进分片迁移；分片键选 `id` 让 CAS 单分片命中；CDC 只传 `eventId + traceId`，payload 以 DB 行做唯一事实源 |
| 时间线投递  | RocketMQ **顺序消息**（append/remove 同 topic 同消费者）              | 按 action 拆 tag/消费者会让同帖 append 与 remove 失序 → 「已下架内容重新出现」的内容安全事故                         |
| 对象存储   | 端口 `MediaStorageClient` + 三实现（minio / local / placeholder） | 按配置互斥装配，切换不动业务代码                                                                       |
| 文件校验   | Magic Number 白名单（jpg/png/gif/webp，排除 SVG）                  | 不信任 Content-Type / 扩展名；SVG 可内嵌脚本有 XSS 风险                                               |
| 引擎降级   | `degraded-mode=empty`（默认）                                  | 引擎不可用时**拒绝跨分片广播回源**——回源会把一次依赖故障放大成全库故障                                                 |
| 抽象尺度   | 只为「当下真实存在的职责」建抽象                                           | 已删除 UploadCommand / ExifCleanerProcessor / MediaReviewStateMachine 等过度设计               |
| 错误响应   | 统一 `Result` + 全局异常处理器                                      | Controller 不感知错误拼装；堆栈只进日志不外泄                                                           |

## 4. 通信矩阵

| 链路                                         | 方式                   | 说明                                                                                                                                                                 |
| ------------------------------------------ | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| UI → tf-gateway                            | 同步 HTTP + JWT        | 唯一对外业务端口                                                                                                                                                           |
| tf-gateway → tf-feed-engine（**读**）         | 同步 HTTP              | 仅 `GET /internal/feed/recommended`。已配 connect 500ms / read 2s 超时，失败按 `degraded-mode` 降级                                                                            |
| tf-gateway → tf-feed-engine（**写**：入流 / 下架） | 异步 RocketMQ 顺序消息     | `append` / `remove` 共用 topic 与消费者，靠 `action` 区分；`syncSendOrderly(hashKey=timelineKey)` + `ConsumeMode.ORDERLY`。**MQ 关闭时**退回同步 HTTP 兜底（`HttpFeedTimelinePublisher`） |
| tf-gateway → 上传事件（触发审核）                    | 本地事件 / MQ            | `MediaEventPublisher` 端口；`mq.enabled=false` 走进程内 `@Async`                                                                                                          |
| tf-gateway / tf-feed-engine → Redis        | 同步（Lettuce）          | 限流、并发护栏、敏感词与状态缓存、公域时间线                                                                                                                                             |
| tf-gateway → MySQL                         | 同步（ShardingSphere）   | 16 张逻辑表分片（7×hashMod4 + 9×hashMod128，含 user_follow / user_fans / outbox_event）+ 12 张单表                                                                        |
| tf-gateway → MinIO                         | 同步（S3 协议）            | 图片对象读写                                                                                                                                                             |
| 服务间鉴权                                      | **X-Internal-Token**  | `/internal/**` 两侧 `InternalTokenInterceptor` 校验 `X-Internal-Token`（值来自 `TURBOFEED_INTERNAL_TOKEN`）；**缺令牌仅 `dev`/`local` 允许 fail-open（启动打印 banner 告警），其余 profile 启动即失败**（M0 收紧，见 service-split.md §8）                                                       |
| 服务发现                                       | Nacos **opt-in**      | 默认直连 `base-url`；`spring.cloud.nacos.discovery.enabled=true` + 服务名走 `@LoadBalanced` 客户端，已做多实例注册/LB/故障摘除验证 |
| Outbox CDC                                     | Canal → RocketMQ      | `outbox_event` binlog → Canal → 转发器（按 `aggregate_id` 保序）→ `turbofeed-outbox-cdc` → `tryDeliver`；默认关，Relay 保留兜底 |
| 服务间观测                                      | actuator health/info + Prometheus | Micrometer 指标已暴露；集中日志/全链路 Tracing 仍待演进                                                                                              |

> 当前**面向用户的同步跨服务读主要是推荐流**；时间线写入已异步化，另有少量内部 HTTP 回调（审核信号/晋级复审）。  
> 这也是「服务间通信用 HTTP 还是 RPC」收益有限的原因：高频写路径走消息解耦，低频同步调用换协议解决不了依赖抖动，只能靠超时、降级与异步补偿。

## 5. 演进路线

| 阶段          | 内容                                                                                                                         |
| ----------- | -------------------------------------------------------------------------------------------------------------------------- |
| 已完成 | 网关业务闭环（认证/RBAC / 一帖多图 / 审核 / 举报申诉 / 评论/点赞/收藏/关注/搜索/通知）、三服务结构、推荐漏斗 G1–G9、公域时间线读模型、RocketMQ 顺序投递、tf-counter、tf-hotspot SDK、Nacos opt-in 服务发现与 Sentinel 热推、`/internal/**` 令牌校验、Outbox 128 分片 + CDC 端到端验证、技术文档体系 |
| Phase 2（近期） | 传统扇出蓝图（收件箱 / 大 V outbox / 活跃度分层 / 多路归并）落地与否的决策；outbox CDC 生产灰度；通知 WS/SSE + 前端工程化；集中日志与全链路 Tracing |
| Phase 3 | ClickHouse 出仓 + T+1 画像回灌、多机房容灾、容量规划与压测报告（tf-benchmark 驱动） |
