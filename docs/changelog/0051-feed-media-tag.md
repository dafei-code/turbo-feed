# 0051 · 内容标签持久冷存 media_tag（分片落库 + 审核链路接线）

日期：2026-09-28　前置：0050（Phase 1b 兴趣画像真·Redis 验证）

## 背景与定位

Phase 1b 的推荐个性化读路径依赖 Redis 双向索引 `tf:tag:media` / `tf:media:tags`
（见 0049/0050）。Redis 是<b>读源、权威</b>，但它是内存态——重启/迁移后需有可重建的
系统记录回填。本变更补上这张<b>可重建冷存表 `media_tag`</b>：

- 与 Redis 同源：同一组 `(post_id, tag)`，落库口径 = 引擎 `timelineKey`（有 post_id 用
  post_id，历史单图回退 media_id），标签由 caption `#话题` 解析、小写归一。
- <b>不进入推荐排序</b>：MVP 阶段 `weight` 统一落 `1.0`；真实加权累积在 Redis
  `tf:user:interest`（见 `InterestService`）。本表仅作冷备份 + 运维/重建回流源。
- <b>失败不阻塞主链路</b>：media_tag 是派生可重建数据，写失败绝不能把「过审→入公域」
  的发布事务 rollback（见下方接线约定）。

## 分片模型（与 media 表同分布）

逻辑表 `media_tag`，分片键 `post_id`（≠ media 表的 `user_id`），算法 `hashMod128`
（`Math.abs(post_id.hashCode()) % 128`）；偶数下标落 `turbo_feed_1`(ds_0)、
奇数下标落 `turbo_feed_2`(ds_1)，与 `media_0..127` 完全对齐。

- 物理表 `media_tag_0..127`（各 64 张，共 128），DDL 见
  `deploy/mysql/alter_media_tag.sql`（IF NOT EXISTS，可重复执行、不 DROP），
  并已同步追加进 `deploy/mysql/init-local.sql` 的 DROP-重建初始化块。
- ShardingSphere 注册：两份配置都加了 `media_tag` autoTable
  （`shardingsphere-config-128.yaml` 已入仓；`shardingsphere-config-local.yml`
  为 gitignored 本机运行配置，已同步）。

## 代码变更

- 新增 `tf-gateway/.../repository/MediaTagJdbcRepository.java`（显式构造器，不用 Lombok——
  本构建环境新文件 Lombok 不生效）：
  - `save(postId, tags)` = **先删后插** set 语义（caption 改标签需反映「当前集合」），
    `ON DUPLICATE KEY UPDATE` 兜底幂等；整段 best-effort（catch 仅 warn，不向上抛）。
  - `remove(postId)` = 按 `post_id` 删除，best-effort。
  - `tagsOf(postId)` = 单分片查询，供运维/重建（不进推荐读路径）。
- 接线 `MediaReviewService`（构造器注入 `MediaTagJdbcRepository`）：
  - `publishAppend(...)` 入流时 `mediaTagRepository.save(post.timelineKey(), post.tags())`；
  - `removeFromTimeline(...)` 下架时 `mediaTagRepository.remove(key)`（`key` 与投递同口径）。

## 验证（`target/verify/MediaTagVerify.java`，真·ShardingSphere 路由，20/20 PASS）

加载最小 `media_tag` autoTable 配置（ds_0/ds_1 都指向 3306、hashMod128，避开宕掉的
3307 副本），跑真实 `MediaTagJdbcRepository`，并直连物理库断言行落点：

- save/tagsOf 返回正确标签集合；
- 路由落点正确：行经 `Math.abs(hashCode())%128` 算出物理表，直连验证确在该分片
  （如 `post_verify_java_001 → turbo_feed_2.media_tag_5`，`k_even_a → turbo_feed_1.media_tag_48`，
  交叉覆盖偶数/奇数两种 db 落点）；
- set 语义：二次 save 替换旧标签、物理行数同步收敛；
- remove：逻辑 `tagsOf` 与物理表行均清零；
- 空标签 safe：save 空集合不落行、不报错。

结论：`media_tag` 落库路由与审核链路接线经真实 ShardingSphere 实证通过；编译
（`mvn -pl tf-gateway -am compile`）零报错（含顺带修复的两处 `MediaItem` 9 参构造器
遗留调用——加 `tags` 字段后漏改，已补 `CaptionTagParser.parse(caption)` 第 10 参）。

## 未决

- 全链路 e2e（上传→过审→发布→media_tag 真实落库→推荐召回）仍需双 MySQL 数据源 +
  双应用 + Redis/MinIO/RocketMQ 全拉起，另案。本变更已保证：配置注册、物理表已建
  （`alter_media_tag.sql` 已对 3306 执行，64+64 张）、路由经 ShardingSphere JDBC 实证。
- 3307 副本当前未起（READWRITE_SPLITTING 读路径会失败）；仅影响读副本，写主库 3306 正常，
  media_tag 落库不受阻。
