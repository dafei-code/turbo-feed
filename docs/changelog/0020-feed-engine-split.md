# 0020 · B1 真拆第一刀：Feed 时间线读模型迁入 tf-feed-engine

> 日期：2026-09-13
> 范围：架构评审后选定的「B 路线（真拆）」首个落地单元。网关退化为「接入 + 审核状态机 + 降级决策」，
> 不再持有任何公域 Feed 读模型。计数（tf-counter）涉及写路径与 DB，留到下一刀。
> 提交：`402e57d`

## 一、背景：为什么要拆，以及为什么先切 Feed

19 号改造把公域读从「跨分片广播扫全表」换成了「写时物化时间线」，但读模型仍与接入层同进程：
两者的**负载特征完全不同**——接入层是短请求、多阻塞点、强依赖下游；Feed 读是纯内存 ZSET 读、
可缓存、可降级。放在一起，任何一方扩容都要连带另一方，且 Feed 的内存占用直接挤压接入层堆。

拆分的候选模块有三个：Feed 时间线、互动计数（tf-counter）、机审（tf-hotspot）。
**先切 Feed 的理由**：它是唯一同时满足三项条件的模块——读多写少、负载特征与接入层正交、
且读模型已独立成型（19 号已写好），因此拆分收益最大、改动面最小。

## 二、改造内容

### 1. 契约下沉（tf-shared）
- 新增 `com.turbofeed.shared.model.FeedItemView`，字段与网关原 `MediaItem` 的 JSON 绑定逐字一致
  （`mediaId` / `url` / `status` / `createdAt` / `caption` / `captionMark`）→ `tf:feed:tl:*` 中
  **已物化的历史成员串无需任何数据迁移**，新旧实例可灰度混跑。
- `status` 刻意用 `String` 而非枚举：审核状态机是网关的内部概念，提到契约层会让引擎被其演进绑死。
- `tf-shared/pom.xml` **单独**开启 `maven-compiler-plugin` 的 `-parameters`：record 反序列化依赖
  类文件保留形参名（Jackson 经 ParameterNamesModule 读取）。这样既不必引入 `jackson-annotations`、
  保住本模块「零第三方依赖」定位，也无需改根 pom 的全局编译参数。
  已用 `javap` 验证 class 的 `MethodParameters` 属性确实含真实形参名。

### 2. 引擎侧（tf-feed-engine，8083）
- `FeedTimelineStore` 由 `gateway.service.feed` 迁入 `feedengine.timeline`：分桶 key、score 取入流时刻、
  反查索引精确 `ZREM`、`append` 先摘旧位置、桶与索引 7 天 TTL —— P0-3 的全部修复原样搬入，语义零变更。
- 新增 `RecommendedFeedService`：`tf:feed:rec:*` 旁路缓存（TTL 15s）**随数据同域迁入引擎**。
  保留在网关会引入两层缓存「各自过期、失效要跨进程通知」的复杂度，得不偿失。
- 新增 `FeedTimelineController`：`/internal/feed/{recommended, timeline/append, timeline/remove}`，
  与对外 `/api/**` **物理路径区分**，便于在网关/网关前置层拒绝外部直达。

### 3. 网关侧
- 新增 `gateway.client.FeedEngineClient`：**防腐层**。对外仍暴露 `MediaItem`，内部完成
  `MediaItem ⇄ FeedItemView` 映射（`FeedItemMapper`）。契约演进不会渗透到业务代码。
- `FeedEngineProperties`：`base-url` 默认 `http://localhost:8083`、`connect-timeout=500ms`、
  `read-timeout=2s`。**同步调用必须设超时**——引擎故障时若无限等待，网关线程池会被慢调用耗尽，
  这是雪崩入口，不是可调参数。
- 对外 `GET /api/feed/recommended` 的**路径与响应结构完全不变 → 前端零改动**。

### 4. 降级口径（P0-4 的落地）
新增开关 `turbofeed.feed.degraded-mode`：

| 取值 | 行为 | 适用 |
|---|---|---|
| `empty`（默认） | 返回空列表 + WARN 日志，**拒绝跨分片广播** | 生产必须保持 |
| `local-scan` | 回源 `listApprovedGlobal` 广播查全部分片（等价拆分前行为） | 仅本地/演示 |

默认值刻意选 `empty` 而不是「回源」：引擎不可用时回源扫全分片库，会把一次依赖故障
放大成全库故障（正是 19 号要消灭的模式）。

## 三、自测
- 7 模块 `mvn compile` 通过；`javap` 验证契约 record 形参名、`-parameters` 生效。
- 用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起「假引擎」对 `FeedEngineClient` 做真实往返验证
  （零外部依赖）：覆盖契约反序列化与字段映射、两种「空」的区分（空列表 vs 引擎不可用）、
  业务失败码 / HTTP 5xx / 连接拒绝三条降级路径、**读超时是否真的生效**、写侧 path+query+body 组装。
- 端到端：网关 8080 → 引擎 8083，上传 → 审核 → 公域 feed 出现 → 下载架。

## 四、已做 vs 延后
- ✅ 已做：契约下沉、引擎独立部署单元、防腐层客户端、超时与降级口径。
- ⏸ 延后（B2）：投递侧仍是「同步 HTTP + fail-open」，引擎抖动时内容**静默不入流**
  （无重试、无削峰、调用方拿不到失败）→ 见 `0021-feed-timeline-rocketmq.md`。
- ⏸ 延后：`/internal/**` 当前**无鉴权**，依赖内网信任；生产需补服务间令牌 / mTLS。

## 五、影响文件
- 新增：`FeedItemView`（tf-shared）、`FeedEngineClient`、`FeedItemMapper`、`FeedEngineProperties`、
  `FeedTimelineController`、`RecommendedFeedService`
- 迁移：`FeedTimelineStore`（gateway → feedengine）
- 修改：`FeedController`、`MediaQueryService`、`MediaReviewService`、`MediaUploadService`、
  两侧 `application.yml`、`tf-shared/pom.xml`、`tf-feed-engine/pom.xml`、`docs/architecture/service-split.md`
