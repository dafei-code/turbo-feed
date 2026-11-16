# 热点帖计数 Key 分槽（优化项③）

> **默认关闭**。`turbofeed.like.count-sharding-enabled=false` 时，点赞 / 评论点赞仍走
> 原单 Key `tf:like:count:{postId}` / `tf:comment:like:count:{commentId}`，行为与改造前逐字节一致。
> 本文描述的是**可选的性能档**，不是可靠性修复；可靠性修复见 PR-A / PR-E 与
> `docs/ops/burst-mq-profile.md`。

## 一、为什么默认关闭

原设计在 `MediaLikeService` 类注释里写得很明确：计数 Key 只存一个整数，`INCR` 恒 O(1)、
没有去重 Set 那种「百万元素挤在一个 Key」的膨胀问题，打散它只会把「读一次点赞数」
放大成 64 个 Key。因此**默认形态是正确且更省的**。

只有当爆款单帖把「点赞 + 取消 + 读 + 评论 + 评论赞」集中到同一个 Redis slot，
实测该 slot 所在节点 CPU 先于其它节点打满时，分槽才有收益。这是一个
**用读放大换写热点的取舍**，不是普适优化。

> ⚠️ 评审原文说「五类操作共用同一个 slot」并不准确：Redis Cluster 的 slot 由
> `CRC16(key)` 决定，不同 Key 通常落在不同 slot。真正的风险是**同一批热 Key 集中到同一个节点
> 的少数 slot**，而不是字面意义的「同一个 slot」。

## 二、数据模型

| Key | 含义 | TTL |
| --- | --- | --- |
| `<legacyPrefix><id>` | 旧单 Key（开启前的累计快照），开启后**不再写入** | 30d |
| `<legacyPrefix><id>:s:base` | 开启分槽瞬间把旧单 Key 的值搬过来的一次性基线（SETNX 迁移） | 30d，读写续期 |
| `<legacyPrefix><id>:s:{0..63}` | 各槽**净增量**（可正可负），槽号 = `floorMod(Long.hashCode(userId), 64)` | 30d，写时续期被触碰槽 |

**真实总数 = `base + Σ(64 槽)`**。

用「净增量」而不是各槽存绝对值，是为了让 `INCRBY` 对不存在 Key 从 0 起算这件事天然正确：
旧单 Key 版必须靠 Lua `EXISTS` + 64 槽 `SCARD` 做冷启动校正，分槽版完全不需要。
取消赞让某槽变成 `-1` 是合法且可逆的。

**Key 刻意不加 hash tag**：64 个槽必须散到不同 Redis slot，否则等于没打散。
代价是不能用跨 Key Lua（会 `CROSSSLOT`），所以读用 `executePipelined` 把 65 个 `GET`
合成一次往返；单槽 `INCRBY` 本身原子，读是最终一致快照，不要求跨槽事务。

## 三、读放大定量（开前必须算）

| 路径 | 单 Key 形态 | 分槽形态 |
| --- | --- | --- |
| 内容详情页 `likeCount` | 1 次 `GET` + 1 个 64 槽 `EXPIRE` pipeline | 1 次 `GET` base（ensureBase）+ 1 个 65 项 `EXPIRE` pipeline + 1 个 65 项 `GET` pipeline |
| 列表页 `likeCounts`（N 条） | 1 次 `MGET`（N 个 Key） | 1 次探测 pipeline + 1 次 `N × 65` 个 `GET` 的 pipeline |
| 20 条列表页命令数 | 20 | 约 1300（**约 64 倍**） |

结论：**只在 Redis 集群有余量、且热点节点 CPU 已成为实测瓶颈时开启**；
单副本 / 小集群开启大概率变慢。列表批量路径刻意不做 TTL 续期，避免把读放大再翻倍。

## 四、开关

| 变量 | 属性键 | 默认 | 开启 |
| --- | --- | --- | --- |
| `TURBOFEED_LIKE_COUNT_SHARDING_ENABLED` | `turbofeed.like.count-sharding-enabled` | `false` | `true` |

已在 `deploy/compose/docker-compose.prod.yml` 的 **gateway 段**显式转发（compose `environment:`
是白名单，只改 `.env` 不生效）。常量登记在 `FeatureFlagKeys.LIKE_COUNT_SHARDING_ENABLED`，
`FeatureFlagGuardTest` 锁死键名。

同时作用于：
- `MediaLikeService`（前缀 `tf:like:count:`）
- `CommentLikeService`（前缀 `tf:comment:like:count:`）

两者共用 `ShardedLikeCountStore`，槽数固定 64，与去重集合槽号同一散列函数。

## 五、启用步骤

1. 确认前置：Redis 集群健康、监控能看到各节点 CPU，且已确认瓶颈是热点 slot 而非 DB / 线程池。
2. 设置 `TURBOFEED_LIKE_COUNT_SHARDING_ENABLED=true` 并滚动重启 **gateway**（只需网关，
   tf-counter 不感知计数 Key 形态）。
3. 迁移是 **lazy** 的：每个帖子首次被读 / 被点赞时，`ensureBase` 用 `SETNX` 把旧单 Key 的
   快照搬进 base，之后该帖的增量全部落在 64 槽。无需停机、无需全量刷数据。
4. 观察 `likeCount` / `likeCounts` 返回值与开关前是否连续；用 `recountFromSlots(postId)`
   对个别漂移帖做权威重建（走 base 覆盖 + 清空槽，不写旧单 Key）。

## 六、回退（**强制步骤，不可跳过**）

关闭开关前，必须对每个有分槽数据的帖子调用：

```
ShardedLikeCountStore.foldBack(prefix, id)
```

它做三件事：`read` 出 `base + Σ槽` → `SET` 回旧单 Key → 删除 base 与 64 个槽 Key。
然后再把开关置 `false` 并重启 gateway。

> ⚠️ **不回灌直接关闭会丢数据**：关闭后读路径退回旧单 Key，而它在开启期间从未更新，
> 值是「开启前的快照」，开启期间的全部增量会凭空消失。这是分槽形态最重要的运维约束。
>
> 回灌后分槽 Key 被删除，即使开关仍开着，下次访问 `ensureBase` 也会从已更新的旧单 Key
> 重新取到正确基线，因此回灌可重复执行、可分批执行。

## 七、验证清单

| # | 检查项 | 预期 |
| --- | --- | --- |
| 1 | 默认关闭跑 `MediaLikeServiceTest` / `CommentLikeServiceTest` | 全绿，且读路径仍是单 Key `MGET` |
| 2 | 开启后点赞一次再读 | 总数 = 开启前值 + 1；`base` 存在，仅一个槽有净增量 |
| 3 | 取消赞 | 该槽净增量变 0 或 -1，总数回退正确 |
| 4 | 列表页 20 条 | 一次 pipeline 返回 1300 个结果且按 `项序 × 65` 对齐，无串项 |
| 5 | `recountFromSlots` | base 被写成 64 槽去重集合基数之和，槽清空 |
| 6 | `foldBack` 后关开关 | 旧单 Key 值 = 回灌前分槽总数，重启后读数不跳变 |
| 7 | 压测对比 | 热点节点 CPU 下降，但整体 Redis 命令数上升约 64 倍，需确认收益为正 |

> 本机 Docker daemon 未运行，本轮只完成编译 / 单测 / 静态校验；
> 上表 2–7 的**真启动与压测仍待有 Redis 集群的环境执行**。

## 八、已知边界

| 边界 | 说明 |
| --- | --- |
| 回退需显式回灌 | 见 §六，这是唯一硬性运维约束 |
| 进程崩溃窗口不变 | 分槽只改 Key 形态，不改「异步落库 fail-open」语义；可靠性仍靠 PR-A / PR-E |
| 长期只读不写 | 详情页读路径会续期 base 与 64 槽；列表页不续期，连续 30 天只被列表页读到的帖子可能过期，需 `recountFromSlots` 重建 |
| 与突发档互不依赖 | MQ 削峰（`docs/ops/burst-mq-profile.md`）与分槽可叠加，也可单独开启 |
| 评论读缓存 / 读写分离 | 见优化项④，与计数分槽无耦合 |
