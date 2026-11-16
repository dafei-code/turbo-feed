# 优化项④：评论热帖读路径扩展（短 TTL 页缓存 + 读写分离副本）

> 状态：**已实施，默认关闭 / 默认回落主库**。对应评审「评论热帖读路径加短 TTL 缓存或启用读写分离副本」。
> 配套代码：`CommentPageCache`、`CommentService.loadPage`、三个落库入口的 `evict` 接线、
> `deploy/compose/docker-compose.prod.yml` 的副本 URL 参数化。
> 本轮为代码 + 静态/单元验证；真启动与压测见 §七。

---

## 一、结论先行

| 项 | 结论 |
|---|---|
| 瓶颈是否真实 | 是。评论列表直接查 DB，`comment` 按 `media_id` 分 128 片，单帖所有翻页都落到同一张物理表；`idx_media_root_created` 保证不深扫，但单分片读 QPS 有上限 |
| 评审里需修正的一句 | 「128 配置里的读写分离尚未启用」不准确：`shardingsphere-config-128.yaml` 早已有 `!READWRITE_SPLITTING`，真正缺的是**部署侧真实副本 URL** |
| 本轮做了什么 | ① 新增 opt-in 短 TTL 评论页缓存（默认关）；② 把 prod compose 里硬编码到主库的副本 URL 参数化，显式设置即可真正分流 |
| 默认行为 | 完全零回归：缓存开关关闭时不产生任何 Redis 读写；副本变量未设置时 `ds_*_read` 仍指向主库 |
| 是否解决「丢数据」 | 否。丢数据根因由优化项①（MQ 突发档）、PR-A、PR-E 处理；本项只解决**读性能** |

---

## 二、开关清单

| 属性键（`FeatureFlagKeys`） | 环境变量 | 默认值 | 作用 |
|---|---|---|---|
| `turbofeed.comment.page-cache-enabled` | `TURBOFEED_COMMENT_PAGE_CACHE_ENABLED` | `false` | 评论列表页短 TTL 缓存总闸 |
| `turbofeed.comment.page-cache-ttl-ms` | `TURBOFEED_COMMENT_PAGE_CACHE_TTL_MS` | `2000` | 缓存 TTL，代码内 clamp 到 `200ms ~ 30s` |
| `TURBOFEED_REPLICA_DB_0_URL` / `_1_URL` | 同名 | 主库 `mysql:3306` | `ds_0_read` / `ds_1_read` 的 JDBC URL |

> compose 的 `environment:` 是白名单：以上变量都已在 `docker-compose.prod.yml` 的 `tf-gateway` 段显式转发，
> **只改 `.env` 不改编排不会生效**（历史坑：`TURBOFEED_BEHAVIOR_LOG_ENABLED`）。模板见
> `deploy/env/.env.multinode.example` 的「评审优化项④-a / ④-b」两段。

---

## 三、方案 A：短 TTL 评论页缓存

### 3.1 缓存边界（关键设计）

| 缓存对象 | 是否缓存 | 原因 |
|---|---|---|
| `List<Comment>` DB 行快照（「算 items」那一段） | ✅ 缓存 | 这是唯一真正打 DB 的部分，包含热头回表 `listByIds`、首页回退 `listTopLevelFresh`、深页 `listTopLevelKeyset`、回复 `listReplies*` |
| 点赞数 `likeCount` | ❌ 不冻住 | `enrichLikeCounts` 在缓存外层执行，仍一次 pipeline 读 Redis 64 槽权威值回填 |
| 评论总数 `total` | ❌ 不冻住 | `CommentCountService.getCount` 仍在缓存外实时读 Redis |
| `nextCursor` / `hasMore` | ❌ 不缓存 | 由 items 现算，避免游标与快照内容在 TTL 内错位 |
| `CommentPage` 整体 | ❌ 不缓存 | 同上 |

> 实现细节：缓存返回值统一 `new ArrayList<>(...)`，因为 `enrichLikeCounts` 会 `items.set(i, ...)`
> 覆盖 `likeCount`；反序列化或 DB 返回不可变列表会直接抛 `UnsupportedOperationException`。

### 3.2 Key 维度

| 维度 | 取值 | 说明 |
|---|---|---|
| `mediaId` | SHA-256 前 16 hex | 形如 `media/{uid}/{uuid}.{ext}` 含斜杠且用户可控，摘要避免超长 Key 与原样入参进 Redis |
| generation | `tf:comment:page:gen:{digest}` 的 `INCR` 值 | 失效时推进，旧代 Key 自然不可达、由 TTL 自净 |
| `rootId` / `size` / `mode` / `cursor` | 原值 + cursor 摘要 | `mode` 取 `newest` / `hot` / `replies`；不同页、不同排序互不串页 |

Key 形如：`tf:comment:page:{mediaDigest}:g{generation}:{rootId}:{size}:{mode}:{cursorDigest}`。
读路径开销：1 次 gen `GET` + 1 次 page `GET`；命中时省掉的正是单分片 DB 查询。

### 3.3 新鲜度口径（必须先看）

1. **缓存是纯短 TTL**（默认 2s）。未失效时列表最多滞后一个 TTL；`hot` 榜的 ZSET 顺序同样最多滞后一个 TTL。
2. **主动失效挂在「落库成功之后」，不在 `publish` 主链路里**。评论落库是异步的
   （`LocalCommentEventPublisher` / `CommentPersistConsumer`）。若在 `publish` 里立刻失效，
   失效后的第一次读发生在 DB 尚无新行时，反而会把「缺新评论」的旧列表重新回填、冻住一个 TTL。
   落库成功后失效，则「新评论可见延迟」与不加缓存时完全一致（都取决于异步落库完成时间）。
3. 失效走 `INCR` generation，**不用 `KEYS` / `SCAN` 删页**：O(1)，不会在 Redis 上做大范围扫描。
4. 失效失败只 WARN：最坏结果是列表滞后一个 TTL，不影响落库正确性。
5. 三处失效接线：
   - `LocalCommentEventPublisher`：进程内异步落库 `inserted == true` 后；
   - `RocketMqCommentEventPublisher`：MQ 发送失败 → 本地幂等兜底落库成功后（正常投递由消费端负责）；
   - `CommentPersistConsumer`：MQ 消费落库成功后（失效放在 try 之外，异常绝不被当成落库失败触发重投）。
   
   重复投递 `inserted == false` 不重复失效；缓存开关关闭时 `evict` 是 no-op。

### 3.4 什么时候开 / 什么时候别开

| 场景 | 建议 |
|---|---|
| 爆款帖评论区翻页读把 `comment_N` 单分片读 QPS 打满，且发布 QPS 相对读 QPS 很低 | ✅ 开 |
| 突发档正在高频发评论（每秒数百条集中在同一帖） | ❌ 别开：每次落库都推进 generation，命中率被自己打没，只剩 Redis 往返开销 |
| Redis 集群本身已被热点 slot CPU 打满 | ❌ 先做优化项③（计数分槽）或扩容，别把压力搬进 Redis |
| 要求「发布后列表强一致立即可见」 | ❌ 不做强一致承诺；抖音式列表为最终一致，前端用乐观插入承接 |

### 3.5 启用 / 验证 / 回滚

启用（在 `deploy/env/.env`）：

```
TURBOFEED_COMMENT_PAGE_CACHE_ENABLED=true
TURBOFEED_COMMENT_PAGE_CACHE_TTL_MS=2000
```

验证清单：

| # | 检查 | 期望 |
|---|---|---|
| 1 | `tf_comment_page_cache_total{result="hit"/"miss"/"error"}` | hit 随重复翻页上升；error 不持续增长 |
| 2 | 同一 `mediaId` 连续两次相同分页请求，打开 SQL 日志 | 第二次无 `comment` 表查询 |
| 3 | 发布一条评论后立即刷新列表 | 落库完成后可见；可见延迟与未开缓存时一致 |
| 4 | 发布后给评论点赞，再读列表 | `likeCount` 实时变化（未被 TTL 冻住） |
| 5 | 不同 `size` / `cursor` / `mode` 组合各读一次 | 互不串页 |
| 6 | 停掉 Redis 后读列表 | fail-open 回源 DB，无 5xx，只有 WARN |
| 7 | `tf_comment_page_cache_evict_total` | 每成功落库一条评论 +1（重复投递不增加） |

回滚：把开关设回 `false` 并重启网关即可；**不需要清 Redis**——旧页 Key 带 generation 且 TTL ≤ 30s，
会自行过期。`tf:comment:page:gen:*` 这类 generation Key 不设 TTL（数量 = 有评论的 mediaId 数，可控），
如确需清理，按前缀单独处理，切勿 `FLUSHDB`。

---

## 四、方案 B：读写分离副本参数化

### 4.1 澄清一个事实

`tf-gateway/src/main/resources/shardingsphere-config-128.yaml` 中 `!READWRITE_SPLITTING` 规则**早已存在**：

| 配置 | 值 |
|---|---|
| `ds_0` | `writeDataSourceName: ds_0_primary`，`readDataSourceNames: [ds_0_read]` |
| `ds_1` | `writeDataSourceName: ds_1_primary`，`readDataSourceNames: [ds_1_read]` |
| `transactionalReadQueryStrategy` | `PRIMARY`（事务内读走主库，规避写后读复制延迟） |

缺的从来不是规则，而是部署侧真实副本地址。改之前 `docker-compose.prod.yml` 把
`TURBOFEED_REPLICA_DB_0_URL` / `_1_URL` **硬编码到主库 `mysql:3306`**，所以读路径实际一直走主库。

### 4.2 本轮改动

prod compose 两个变量改为 `${TURBOFEED_REPLICA_DB_0_URL:-<主库URL>}` / `_1` 形式：
**默认值刻意仍是主库**，保证未部署副本时读路径照常可用（等价于「规则存在但读主」），
避免像直配本机 `3307` 那样因副本不存在导致读全量失败（`docs/changelog/0051` 实证过）。

只有显式设置才真正分流。示例（`host` 换成部署环境真实副本地址，JDBC 参数与主库一致）：

```
TURBOFEED_REPLICA_DB_0_URL=jdbc:mysql://mysql-replica-0:3306/turbo_feed_1?...
TURBOFEED_REPLICA_DB_1_URL=jdbc:mysql://mysql-replica-1:3306/turbo_feed_2?...
```

> 根目录 `docker-compose.yml` 仍把副本指到主库，那是**本地开发编排**，本轮不改；
> 生产以 `deploy/compose/docker-compose.prod.yml` 为准。

### 4.3 启用前检查 / 回滚

| # | 检查 | 期望 |
|---|---|---|
| 1 | 两个副本已配好原生复制，`Seconds_Behind_Master` 稳定 | 复制健康 |
| 2 | 网关容器内可解析并连通副本 host | `jdbcUrl` 可建连 |
| 3 | 评论列表 / 搜索等非事务读 | 落到副本（可用副本 `general_log` 或连接数验证） |
| 4 | 发布评论后事务内的父评论校验 | 仍走主库（`PRIMARY` 策略） |
| 5 | 复制延迟飙升时列表出现「刚发的评论晚一点可见」 | 属预期；必要时切回主库 |

回滚：删除这两个环境变量（或指回主库 URL）并重启网关。改的是连接目标，不动任何数据。

---

## 五、两者怎么配合

| 组合 | 效果 |
|---|---|
| 都不开（默认） | 当前形态：读全打主库单分片，日常千级 QPS 够用 |
| 只开副本 | 读压力摊到副本，不引入缓存新鲜度问题；**优先推荐先做这一步** |
| 只开缓存 | 读压力搬进 Redis，列表最多滞后 TTL；适合副本暂不可用但 Redis 有余量 |
| 都开 | 读路径先命中 Redis，miss 再落副本；适合真正爆款单帖，但要同时盯复制延迟与缓存命中率 |

---

## 六、指标

| 指标 | 含义 |
|---|---|
| `tf_comment_page_cache_total{result="hit"}` | 命中次数 |
| `tf_comment_page_cache_total{result="miss"}` | 回源并回填次数 |
| `tf_comment_page_cache_total{result="error"}` | Redis/Jackson 异常（fail-open）次数 |
| `tf_comment_page_cache_evict_total` | generation 推进次数（≈ 成功落库的评论数） |

---

## 七、已知边界 / 未完成验证

- **真启动验证未完成**：本机 Docker daemon 未运行，MySQL / Redis 端口未监听；§三、§四 的验证清单需在
  有中间件与真实副本的环境执行。本轮只做了编译、单测、`docker compose config`、YAML/属性接线静态断言。
- **压测未完成**：缓存命中率、单分片读 QPS 下降幅度、generation 频繁推进对命中率的影响都没有实测数据，
  上线前应在预发用「单帖读多写少」与「单帖读写都高」两种模型各压一轮。
- **缓存 value 含评论正文**：与 Redis 中已有业务数据同等级别，按现有 Redis 访问控制管理。
- **空结果也缓存**：短 TTL 内可挡穿透，但若 DB 恰好在 TTL 内恢复，新评论最多晚一个 TTL 出现。
- **副本地址指错会让读全量失败**（写主库不受影响），所以默认值刻意回落主库，不提供「自动探测副本」。
- 本项不解决丢数据；评论落库严格性见 PR-A，计数 flush 快照见 PR-E，突发档见 `docs/ops/burst-mq-profile.md`。
