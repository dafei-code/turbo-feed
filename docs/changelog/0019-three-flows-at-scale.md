# 0019 · 上传/审核/查看三功能规模化改造（扛住百亿）

> 日期：2026-09-07
> 范围：聚焦「上传 / 审核 / 查看」三大核心闭环，使其具备百亿级承载；互动计数、推荐个性化、多机房等列为后续功能延后。

## 一、背景：当前三功能为何扛不住百亿

| 功能 | 改造前 | 百亿级死穴 |
|---|---|---|
| 上传 | `LocalDiskStorageClient` 落本地磁盘 | 单机目录不可能存百亿文件/ PB 级容量，且多实例不共享 |
| 审核 | `handleUploaded` 内**同步** `insert→review`（传完即 APPROVED） | 审核重 IO 阻塞上传线程；无真实机审/异步；无背压容错 |
| 查看 | 公域流 `listApprovedGlobal` = **跨分片广播扫全表** | 每翻一页广播到全部分片归并，百亿行直接拖垮所有分片 |

## 二、改造内容

### 1. 上传：对象存储（MinIO / S3 协议）
- 新增 `MinioStorageClient` 实现 `MediaStorageClient` 端口，`turbofeed.media.storage=minio` 激活；默认仍 `local`（克隆即跑）。
- 百亿文件的唯一可行落点：对象存储天然横向扩容，URL 直指公网/CDN，网关不过字节流。
- `MediaProperties` 增 `minio` 子配置（endpoint/accessKey/secretKey/bucket）；pom 增 `io.minio:minio:8.5.7`（仅 minio 模式加载，不影响默认 local）。
- `ImageFormat` 补 `contentType()`，写入对象存储时设置正确 MIME。

### 2. 审核：异步 + 机审端口
- `AsyncConfig`（`@EnableAsync` + `reviewExecutor` 线程池，拒绝策略 `CallerRunsPolicy` 背压，绝不丢事件）+ `ReviewListener` 加 `@Async` → 审核脱离上传线程，上传接口仅做「落存储 + 发事件」即返回受理。
- 新增 `ContentModeration` 端口 + `AutoPassModeration` 默认实现（占位自动通过，预留接内容安全 API）；`MediaReviewService.handleUploaded` 改为 `insert PENDING → 机审 → review(结果)`，保持幂等（重复事件不二次流转）。
- RocketMQ 模式（跨进程）下消费本就在独立线程，与原 @Async 本地模式激活互斥，逻辑共用。

### 3. 查看：写时物化时间线，消灭广播扫描（核心）
- 新增 `FeedTimelineStore`：审核通过（APPROVED）时把**非范式** `MediaItem` 写入 Redis ZSET `tf:feed:tl:{yyyyMMdd}`，score=approvedAt 毫秒。
- `MediaQueryService.listRecommended` 改读时间线：`ZREVRANGEBYSCORE` 游标分页，合并近 3 天分桶，每请求 **O(log n) 级、零扫 media 分片库**。这就是 README「Feed 引擎推模式」的网关内雏形。
- 按天分桶避免单 ZSET 无限膨胀；Redis 不可用/时间线空时**降级**回源 `listApprovedGlobal`（非常态路径），fail-open 不阻断读。
- 个人中心 `listByUser` 仍按 `user_id` 精准单分片（本就单分片，可扩展）；单条状态 `statusOf` 走 P2 旁路缓存。

## 三、元数据分片扩容（百亿行落地，可选切换）
- 提供 `shardingsphere-config-128.yaml`（media 表 2 库 × 64 表 = 128 物理表）+ `db/expand_media_tables_128.sql`（128 张建表 DDL）。
- **当前运行的 4 表配置不变**；本配置为扩容就绪件。切换须先建表再做存量再平衡（分片数 2→64 会改变 hash 路由，勿硬切）。
- 因公域读已不扫分片库，扩容主要服务写入分布与个人中心单用户查询；128 表下单表约千万行，B+Tree 友好。

## 四、配置与自测
- 对象存储（可选）：`turbofeed.media.storage=minio` + `turbofeed.media.minio.*`（endpoint/accessKey/secretKey/bucket），生产 secret 走环境变量/密钥管理。
- 默认 `local` 仍可端到端演示；Redis 需启动（Docker）以承载时间线缓存，未启动则公域流降级回源。
- IDEA(JDK17) 重启 GatewayApplication 后验证：上传→个人中心状态 PENDING→APPROVED→公域 feed 出现；Redis 不可用时应降级可见。

## 五、已做 vs 延后（透明边界）
- ✅ 已做：对象存储接入点+客户端、审核异步+机审端口、公域读去广播、分片扩容就绪件。
- ⏸ 延后（用户明确「其他功能」）：真实内容安全机审（当前 AutoPass 占位）、互动计数（tf-counter）、推荐个性化/recsys、多机房多活、CDN、RocketMQ 异步化跨进程（端口已就绪，启 `turbofeed.mq.enabled=true` 即可）。

## 六、影响文件
- 新增：`MinioStorageClient` / `AsyncConfig` / `ContentModeration` / `AutoPassModeration` / `FeedTimelineStore` / `shardingsphere-config-128.yaml` / `db/expand_media_tables_128.sql`
- 修改：`MediaProperties` / `ImageFormat` / `ReviewListener` / `MediaReviewService` / `MediaQueryService` / `pom.xml`
