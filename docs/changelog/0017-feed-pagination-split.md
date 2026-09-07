# 0017 - P1.5 media 查询分页化 & 公域推荐流拆分

日期：2026-09-07
阶段：P1 落地后的查询优化（仍限单机 / 零新增依赖）

## 背景

P1 将 media 元数据落库到 MySQL 分片（按 `user_id` 分片键），解决了「重启即丢」。
但在「对标抖音级并发」的目标下，P1 的查询层有两个结构性缺陷，P1.5 先修掉可立即见效的部分：

1. `GET /api/media/mine` 原实现 `SELECT ... WHERE user_id = ? ORDER BY created_at DESC`，
   **无 LIMIT/OFFSET、无 status 过滤**，一次回吐用户全部内容；前端再在内存里 `filter(APPROVED)`。
   用户上传量一大即拖垮带宽与 JVM 内存。
2. `feed.html` 直接复用「个人中心」接口 `/api/media/mine` 充当公域推荐流。
   按 `user_id` 分片的表最适合「查某人自己的内容」，**最不适合**「跨全平台挑内容做推荐」——
   公域推荐流若长期复用该接口，会倒逼出大量跨分片扫描，与分片设计初衷相悖。

## 改动

| 文件 | 动作 | 说明 |
|---|---|---|
| `repository/MediaJdbcRepository.java` | 改 | `listByUser` 增 `status` 过滤 + `LIMIT/OFFSET` 分页；新增 `listApprovedGlobal` 跨分片广播查全平台 APPROVED |
| `service/query/MediaQueryService.java` | 改 | `listByUser(userId,status,page,size)` 透传分页；新增 `listRecommended(page,size)` |
| `controller/MediaController.java` | 改 | `mine` 增可选 `page/size/status` 参数（默认 page=0,size=50,status 不过滤） |
| `controller/FeedController.java` | 新 | `GET /api/feed/recommended?page=&size=`（默认 20），与 `/api/media/mine` 解耦 |
| `turbo-feed-ui/feed.html` | 改 | `loadFeed()` 改调 `/api/feed/recommended`，去掉前端 status 过滤 |
| `docs/changelog/0017-feed-pagination-split.md` | 新 | 本文档 |

## 契约

- `GET /api/media/mine?page=0&size=50&status=APPROVED`（status 可选，不传=不过滤）
  返回登录用户本人内容（按 `user_id` 分片，精准命中单分片），供个人中心使用。
- `GET /api/feed/recommended?page=0&size=20`
  返回全平台 APPROVED 内容（**占位实现**：跨分片广播查询，仅演示/小数据量），供公域 feed 使用。

## 明确不做的（留给后续阶段，避免 P1.5 过度膨胀）

- **Redis 缓存审核状态 / 热点 feed**：`/status` 轮询与 feed 首屏在高并发下必须前置 Redis 拦截。
  本机 Redis 已就绪（Docker 容器 `redis`，6379），但接入需新增 `spring-boot-starter-data-redis`
  依赖，未在本阶段授权，列入 P2。
- **真正的推荐服务 + 异构索引**：`listApprovedGlobal` 的跨分片广播在生产是反模式。
  公域推荐流须由推/拉/混合模式 + ES / 倒排 / feed 预生成宽表提供，列入 P3。
- **媒体文件 CDN 化**：当前 `LocalDiskStorageClient` 返回 `http://localhost:8080/media/...`，
  网关直转字节流；抖音级须改对象存储 + CDN 直链，列入 P3。
- **上传/审核异步化**：当前 `handleUploaded` 同步 `insert + review(APPROVED)`；高峰洪峰须经
  RocketMQ 削峰、独立审核心消费，列入 P2/P4。

## 编译

本机无 `mvn` 且沙箱限制写盘，未做编译验证；请在 IDEA（JDK 17）重建 `tf-gateway` 后运行。
