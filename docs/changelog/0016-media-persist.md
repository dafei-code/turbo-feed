# 0016 - 媒体元数据落库（P1：内存态 -> MySQL 分片）

- 日期：2026-09-07
- 类型：feat(persist) + 内存态退役
- 关联：用户授权「spring-boot-starter-jdbc（新增 1 依赖）+ 我出 DDL、用户在 Navicat 执行」

## 动机

`MediaReviewService.statusRegistry` 与 `MediaQueryService.userIndex` 两个进程内内存态
自 P0 起承载媒体审核状态与「我的上传」索引，**重启即丢失**，仅能演示审核闭环。
P1 将其落到 MySQL 分片（media 表），与已接入的 ShardingSphere 元数据层对齐，
使媒体数据持久化、可跨重启保留。

## 分片策略

- 逻辑表 `media`，分片键 `user_id`（与 `user` 表分片键 `id` 同值、同 HASH_MOD 契约），
  2 库 × 2 表 = 4 物理表 `turbo_feed_1.media_0|1` / `turbo_feed_2.media_0|1`。
- 保证「我的上传」`WHERE user_id=?` 精准命中单分片、不广播；同一用户媒体与其 user 行同片。
- `media_id` 为业务主键（不作分片键）；按 media_id 查询必须同时带 user_id 才精准路由。
- status：TINYINT，0=PENDING 1=APPROVED 2=REJECTED。

## 改动清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `pom.xml` | 修改 | 新增 `spring-boot-starter-jdbc`（JdbcTemplate + HikariCP，版本由 Boot BOM 托管） |
| `db/media_schema.sql` | 新增 | 4 张物理 media 表 DDL（含分片说明与 root 密码提示） |
| `shardingsphere-config.yaml` | 修改 | `rules.!SHARDING.tables` 增 `media`（user_id 分片 + HASH_MOD）；`shardingAlgorithms` 增 `mediaDbSharding`/`mediaTableSharding`；两 `password` 处加注释 |
| `repository/MediaJdbcRepository.java` | 新增 | 注入 JdbcTemplate，实现 insert（ON DUPLICATE 幂等）/ updateStatus / listByUser / getStatus；status 枚举↔TINYINT 互转 |
| `service/review/MediaReviewService.java` | 重写 | 删 `statusRegistry`；`handleUploaded` 改为 `insert(PENDING)` + `review(APPROVED)`；`review` 校验 PENDING→终态 |
| `service/query/MediaQueryService.java` | 重写 | 删 `userIndex`/`index()`；`listByUser`/`statusOf` 改查 media 表（statusOf 透传 userId） |
| `controller/MediaController.java` | 修改 | `status` 接口补传 `userId`（来自 JWT）以精准路由 |
| `service/event/MediaReviewConsumer.java` | 修改 | 删 `mediaQueryService.index(event)`（落库已在 `handleUploaded` 内完成）；移除 `MediaQueryService` 依赖 |
| `service/event/MediaIndexListener.java` | 删除 | 「建内存索引」职责被 `handleUploaded` 的落库取代，避免死代码 |

## 验证

- 前置：用户在 Navicat 对 `turbo_feed_1`/`turbo_feed_2` 执行 `db/media_schema.sql` 建 4 张表；
  若本地 MySQL root 有密码，先在 `shardingsphere-config.yaml` 的两处 `password` 填写。
- 编译：IDEA 内 JDK 17 重新构建 `tf-gateway`（新增 starter-jdbc 依赖需从 Maven 仓库解析）。
- 重启 `GatewayApplication` 后用 `13800138000/123456` 登录 → `user.html` 上传图片 →
  自动过审 → `feed.html` 可见；重启 gateway 后「我的上传」列表仍在（持久化生效）。
- 路由核对：`application.yml` 中 `props.sql-show: true` 已开，观察 INSERT/SELECT 实际落到的
  物理库/表是否符合 `hash(user_id)%2`。

## 影响面

- 仅网关媒体元数据层；上传/存储/鉴权链路不变。
- 新增 1 个 Maven 依赖（spring-boot-starter-jdbc），其余沿用既有 ShardingSphere 数据源。
- 未触碰 `user` 表规则与既有分片算法；media 直接复用 HASH_MOD，无需新写算法类。
