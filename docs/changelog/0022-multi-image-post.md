# 0022 · 一帖多图（一次上传 1..9 张），前端按帖轮播展示

> 日期：2026-09-14
> 范围：把「一张图 = 一条内容」升级为「一次上传批次 = 一个帖子」。上传、审核、我的内容、
> 公域 feed、审核工作台五个环节同步改造，历史数据零迁移。
> 提交：`4903150`

## 一、数据模型

一次上传批次的 N 张图 = N 条 `media` 行，共享同一个 `post_id`，`seq` 为 0 起的帖内序号（决定轮播顺序）。

两个关键约束：

1. **分片键仍是 `user_id`** → 同一帖的 N 张图**必然落在同一分片**，帖内查询永远单分片精准命中、不广播。
   （`post_id` 不是分片键：单凭 `post_id` 查询会广播全分片，故所有帖级查询都必须同时带 `user_id`。）
2. **历史数据零迁移**：存量行 `post_id = ''` 视为「单图帖」，帖身份回退用 `media_id`。
   所有聚合查询统一用 `seq = 0` 取帖代表行（历史行 `seq` 取默认值 0，同样命中），
   **新旧数据走同一段代码**，无需数据回填、无灰度期分支。

## 二、整帖一审

审核以**帖**为单位：机审一次、状态一次翻转、公域时间线一次投递。**整帖同上同下**——
任一图被驳回即整帖驳回，举报 / 下架 / 申诉也作用在整帖上。

这不是实现便利，而是内容安全的必要条件：若允许「一帖内一半可见」，
同一条帖子会在不同入口呈现互相矛盾的可见性，也就出现了「举报了 9 张里的 1 张，其余 8 张继续可见」的绕过路径。

**操作对象统一为「帖代表媒体 ID」**：`media_id` 是主键、`post_id` 不是，因此对外接口（含管理端）
仍以 `mediaId` 为参数，由服务层解析到帖子并落到整帖 —— 这是「接口签名不变、语义升级为整帖」的关键，
前端与管理端**无需改签名**。

## 三、契约与引擎

- `FeedItemView` 增加 `images` / `postId` 与 `timelineKey()`（帖身份，历史数据回退 `mediaId`）。
- `FeedTimelineEvent` 的 `mediaId` 改为 `timelineKey`：**顺序消息的 hashKey 必须是帖身份**，
  否则同一帖的 `append` / `remove` 会按不同 mediaId 哈希到不同队列，失去 B2 建立的顺序保证
  （危及「下架后又出现」的内容安全）。
- `FeedTimelineStore` 以 `timelineKey` 作幂等键与反查索引。

## 四、网关

- **上传返回帖子视图**（`postId` + 按 `seq` 排序的 `images`），不再只回 URL 数组。
- **查询分两步聚合**：先按 `seq = 0` 取每帖代表行并分页（一帖只占一个分页位），
  再按这批 `post_id` 一次性取回帖内全部图片。
  这样既避免 `GROUP_CONCAT` 的长度截断风险，也避免「先拉全表再内存分组」的分页错位与内存放大。
- 新增帖级 `updateCaption` / `updateStatus` / `delete`；**上传中途失败补偿清理**
  （`hardDeleteByPost`），避免留下「缺 `seq=0` 代表行 / 张数不齐」的半成品帖——
  这种帖子既不出现在「我的内容」（代表行不存在）、也无法被用户删除，是最难排查的一类脏数据。
- 上传限额 `turbofeed.media.max-batch-count: 9`，超出返回 `42902 单次最多上传 9 张`。

## 五、前端

- `feed.html`：9 图轮播（横向滑动 + 圆点 + 「n/9」计数 + 自动轮播，手动滑动后暂停）。
- `user.html`：多图选择先入**待传队列**（缩略图预览 / 逐张删除 / n/9 计数 / 超限拦截）再整批提交；
  「我的内容」改为一帖一卡，多图走卡内迷你轮播。
- `review.html` / `admin.html`：待审队列**铺开整帖图片**——原来只画首图，
  在整帖一审的语义下等于让审核员**盲审**（看不到另外 8 张就要对整帖下判断）。

## 六、数据库

`init-local.sql` 与新增 `deploy/mysql/migrate_post_group.sql`：4 张物理表各增加
`post_id VARCHAR(255) NOT NULL DEFAULT ''`、`seq TINYINT NOT NULL DEFAULT 0`、
索引 `idx_user_post(user_id, post_id, seq)`。已在本地库执行并校验 4/4 就位。

## 七、自测
- 7 模块 `mvn compile` 通过；6 个前端页面 `node --check` 通过。
- 端到端 21 项（`/tmp/e2e_multi_image.py`，纯标准库）：
  一次上传 9 图 → 产生**一个** `postId`、`images=9`、状态 `PENDING` →
  「我的内容」该帖**只占一行**且回带 9 图 → 管理员整帖通过 → 整帖翻 `APPROVED` →
  公域 feed 出现且携带 9 图（轮播数据源）→ 整帖删除 → 我的内容消失、
  公域在 15s 旁路缓存 TTL 后最终下架 → 10 张被拒（`42902`）。

## 八、遗留与后续
- 首轮端到端为 20/21，唯一失败项是**审核接口偶发 `50000`（InnoDB 死锁）**——
  由本功能引入的「整帖多行 UPDATE」放大出的并发缺陷，已在下一号修复：
  见 `0023-post-review-deadlock-cas.md`。
- 公域下架为**最终一致**（引擎推荐流 15s 旁路缓存，刻意不做批量失效）：已确认按此口径验收，
  不额外做主动失效。

## 九、影响文件
25 文件 +1506/−330。核心：`MediaJdbcRepository`、`MediaUploadService`、`MediaReviewService`、
`MediaItem`、`FeedItemView`、`FeedTimelineEvent`、`FeedItemMapper`、`FeedTimelineStore`、
`FeedTimelineConsumer`、`MediaController`、`migrate_post_group.sql`、
`turbo-feed-ui/{feed,user,review,admin}.html`
