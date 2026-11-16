# 词库 / 白名单跨实例 epoch 广播（阶段 B2）

> **状态：代码与编排已落地，运行时开关默认关闭；真启动 / 双实例收敛验证被本机 Docker 未运行阻塞。**
> 改动范围：仅 `tf-gateway`（+ `tf-shared` 一个开关常量）、prod compose 一条 `:-false` 转发。
> 验证：`tf-gateway` / `tf-shared` 全量单测 **378 个 0 失败**（含新增 19 个）、`ApplicationContextRunner` 装配测试、编译、`docker compose config`、环境变量接线断言全部通过。

---

## 1. 解决什么

| | 改造前 | 改造后（开关开启时） |
| --- | --- | --- |
| admin 改词库 / 白名单 | 只有**收到请求的那一个实例**重建，其它实例各自等 30s 定时指纹刷新 | 推进全局 epoch + pub/sub 广播，其它实例立即后台重建 |
| 生效时延上界 | 30s（SLO 写 35s） | 一次 Redis 往返 + 一次后台重建；广播丢失时由 30s epoch 轮询补齐 |
| 运维手段 | `POST /reload` 只刷一个实例，只能逐台点 | 任一写端点即全实例生效；`/reload` 仍保留为单实例排障入口 |
| 版本语义 | 无全局版本，各实例只有 `(COUNT, MAX(updated_at))` 启发式指纹 | Redis 单 Key `INCR` 提供全局单调 epoch |

## 2. 读码纠正的三个事实

1. **`WhitelistService` 原本与词库不同构**。它没有 `triggerReload()`、没有单飞 CAS、没有「追平 3 轮」，admin 写路径和定时路径都是同步 `reload()`；`AdminWhitelistController` 旧注释声称「后台异步 / 单飞模型」与实现不符。B2 已把它对齐到词库模型（单线程后台重建 + 单飞 + 追平），`WhitelistRepository.checkSum()` 也合并为单条 SQL 原子取值，消除并发写入时的混合事务快照。
2. **`revision` 不能当 epoch**。它是**行级**审计号（每行独立 +1），没有全局序；`CheckSum(count, MAX(updated_at))` 也不单调（一增一删 count 回退，同秒 UPDATE 推进不了 MAX）。因此必须另立 Redis 原子自增的全局序号。
3. **旧 `multinode-switches.md` §7 把「白名单的 triggerReload 单飞 CAS 与追平 3 轮」列为改造对象**，与代码不符；本次核查后以源码为准并修正了文档。

## 3. 设计

### 3.1 版本源与消息契约

| 项 | 值 |
| --- | --- |
| epoch Key | `tf:mod:dict:epoch`、`tf:mod:whitelist:epoch`（`StringRedisTemplate#opsForValue().increment`） |
| Key TTL | **不设**。与 `RecommendedFeedService` 的 `tf:feed:rec:version` 同口径；设 TTL 会让版本回绕 |
| 频道 | `turbofeed:moderation:dictionary:invalidate`（沿用 `turbofeed:*:invalidate` 命名） |
| 消息体 | `instanceId:kind:epoch`，如 `e8abf783-...:DICT:12`；UTF-8 字符串，无 JSON |
| `kind` | `DICT` / `WHITELIST`，两者独立推进，改白名单不会让词库全量重建 |
| 序列化 | 发布 `convertAndSend` / 订阅 `new String(body, UTF_8)`，与账号状态缓存广播同一套 |

### 3.2 生效链路

1. admin 写端点（词库 5 个 / 白名单 4 个）先完成 DB 写入，本实例提交一次后台异步重建。
2. Controller 调 `ModerationEpochBroker.bump(kind)`：Redis `INCR` → 更新本地已见 epoch → `publish` 一条 `instanceId:kind:epoch`。**必须在 DB 写入之后**，否则接收方可能读到未提交数据。
3. 其它实例订阅到消息：解析后**跳过自广播**、只接受**严格更大**的 epoch（重复 / 迟到 / 乱序一律忽略，绝不让快照回退），然后调用对应 Service 的 `triggerReload()`。
4. `triggerReload()` 单飞 CAS + 单线程后台执行 + 完成后比对指纹最多追平 3 轮；重建异常保留旧快照。
5. 每个实例每 30s 再读一次 epoch Key，本地落后就补一次重建——补 pub/sub 丢消息、实例启动错过消息。

### 3.3 三层保障（为什么不会「更快但更不可靠」）

| 层 | 周期 | 覆盖的故障 | 缺失时的下界 |
| --- | --- | --- | --- |
| pub/sub 广播 | 事件触发，毫秒级 | 正常路径下的跨实例收敛 | 丢消息 → 下一层 |
| epoch 轮询 | 30s | pub/sub 丢消息、实例启动错过、Redis 短暂抖动的补订阅 | Redis 整体故障 → 下一层 |
| 原有 `CheckSum` 定时刷新 | 30s，两个 Service 各自保留 | Redis 完全不可用、epoch Key 被误删 | 退化成改造前行为，不是新增风险 |

启动期 `alignBaselineOnStartup()` 把本地 epoch 基线对齐到 Redis 当前值但**不触发重建**：`@PostConstruct init()` 已从 DB 同步加载过最新快照，避免因历史 epoch 白白重建一次。

**epoch 兜底刻意不用 `@Scheduled`**：Spring Boot 默认调度池只有 1 个线程，词库 / 白名单自身的 30s 指纹刷新也在里面；把一次可能阻塞的 Redis 读挂进去会连带拖住那两个定时器，而它们正是「Redis 整体不可用」时的下界保障。兜底改走独立守护线程 `moderation-epoch-fallback`，开关关闭时连线程都不创建（装配测试已断言）。

### 3.4 失败方向与回环防护

- 全链路 fail-open：Redis 连不上 / `INCR` 失败 / `publish` 失败 / 订阅回调异常，都只记日志，**不阻断 admin 写请求、不清空本地快照**。`bump` 在 `INCR` 成功但广播失败时仍返回新 epoch，由 30s 轮询补齐。
- 订阅方**绝不再次广播**（只调 `triggerReload()`），否则多实例会形成消息回环风暴。
- 订阅容器沿用 `RedisCacheInvalidationConfig` 的成熟骨架：`isAutoStartup()=false` + `SmartLifecycle` 后台每 5s 重试 `start()`，Redis 未就绪**不否决应用启动**；容器 started 后交给内建重连自愈。
- 全局 epoch 回退（例如 Redis 被 `FLUSHDB`）时，发布方与 30s 轮询会跟随回退本地基线，使新一轮广播重新可生效；接收广播时**不允许**回退，防止一条迟到的旧消息把基线拉回去。

## 4. 开关与精确映射

| 属性键 | 环境变量 | 默认 | 门控位置 |
| --- | --- | --- | --- |
| `FeatureFlagKeys.MODERATION_EPOCH_BROADCAST_ENABLED` = `turbofeed.content-security.epoch-broadcast-enabled` | `TURBOFEED_CONTENT_SECURITY_EPOCH_BROADCAST_ENABLED` | `false` | `ModerationEpochBroker` 的 `@Value`、`ModerationEpochConfig` 两个 `@Bean` 的 `@ConditionalOnProperty(havingValue="true")` |

- `application.yml` 写 `${TURBOFEED_CONTENT_SECURITY_EPOCH_BROADCAST_ENABLED:false}`；prod compose 的 `environment:` 是白名单，gateway 段已补 `:-false` 转发，**不接线则设了也不进容器**（同 `TURBOFEED_BEHAVIOR_LOG_ENABLED` 历史坑）。
- `deploy/env/.env.multinode.example` 里写的是多副本目标态 `true`，与 compose 默认 `false` 不冲突：模板要显式注入才生效。
- **零回归**：开关关闭时，`bump` 直接返回、订阅容器与启动器都不装配、30s 兜底立即返回；装配测试已断言「默认关闭时连 `RedisConnectionFactory` 都不需要」。

## 5. 启用步骤

1. 确认 Redis 集群在线且三服务拓扑一致（见 `multinode-switches.md` §3.3）。
2. `cp deploy/env/.env.multinode.example deploy/env/.env.multinode`，保留 `TURBOFEED_CONTENT_SECURITY_EPOCH_BROADCAST_ENABLED=true`。
3. 网关至少两个实例，用同一份 Redis 集群与同一库；启动后查启动日志确认出现 `词库/白名单跨实例 epoch 广播已启用`。
4. 先只开本开关，逐组放量；B1 的 counter / ShedLock / Snowflake 租约按 `multinode-switches.md` §4 单独放量。

## 6. 验证口径

| 检查 | 期望 | 怎么看 |
| --- | --- | --- |
| 开关默认关闭 | 不建订阅容器、无 Redis 写入 | 装配测试 `ModerationEpochConfigTest`；启动日志「未启用」 |
| 广播收敛 | 实例 A 加词后，实例 B 在 1s 内 `matchAll` 命中 | 按 `instanceId` 过滤阶段 A 的 Loki 日志 |
| epoch 推进 | 每次写操作 `INCR` 一次，`DICT` 与 `WHITELIST` 互不影响 | `GET tf:mod:dict:epoch` / `GET tf:mod:whitelist:epoch` |
| 乱序 / 重复 | 旧 epoch 消息不触发重建、不回退快照 | `ModerationEpochBrokerTest` 已覆盖 |
| 广播丢失 | 30s epoch 轮询补齐 | 临时断开订阅后改词，观察 `词库 epoch 兜底触发重建` 日志 |
| Redis 故障 | admin 写仍 200，其它实例 30s 内由 `CheckSum` 兜底 | `词库 epoch 推进失败（退化为各实例 30s 指纹刷新）` 日志 |
| 回环 | 日志里不出现同一 epoch 的重复重建风暴 | 按 `traceId` / `instanceId` 串一轮 |

LogQL 示例（阶段 A 的 Loki）：

```logql
{service="tf-gateway"} | json | msg =~ ".*词库.*|.*白名单.*"
```

> **本轮未做真启动验证**：`docker info` 返回 `Cannot connect to the Docker daemon`，3306 / 3307 / 6379 / 7000-7005 / 9000 全部未监听。上表第 2、3、5、6 项需要 Docker 就绪后起 Redis 集群 + MySQL + 双网关实例实测。

## 7. 回滚

- 去掉环境变量注入（或置 `false`）即回到改造前：开关关闭后不广播、不订阅、不推进 epoch，行为由各实例 30s 指纹刷新接管。
- 代码回滚时同时回退 `WhitelistService` 的异步单飞改动（它改变了 admin 写路径的生效时机，与开关无关）；已新增的 `WhitelistServiceTest` 是这条改动的护栏。
- epoch Key 不承载业务数据，删除只影响版本基线；删除后各实例会在 30s 兜底里跟随回退，期间一致性由 `CheckSum` 定时保障。
- **切勿对承载 epoch Key 的 Redis 执行 `FLUSHDB`**：会让所有实例基线错乱，虽然能自愈，但期间一致性窗口放大到 30s。

## 8. 已知边界

| 边界 | 说明 |
| --- | --- |
| 不是强一致 | pub/sub + 轮询是最终一致，广播只压缩窗口，不提供「写后所有实例立即可读」的线性一致语义 |
| 仍是全量重建 | 每次失效都 `findAllEnabled()` 全量重建（1M 词约 1.2s）；按 revision 范围做增量更新仍属 S4 规划 |
| 词库仍随副本数复制 | 每个 JVM 一份 `AtomicReference<Dictionary>`，副本越多内存线性增长；控制面 / 数据面拆分未做 |
| 依赖单 Redis | epoch Key 与 pub/sub 都在同一 Redis；多机房需版本源与消息总线跨机房复制，本阶段未做 |
| pub/sub 不持久 | 订阅断开期间的消息会丢，只能靠 30s epoch 轮询补；要严格不丢需换 Stream / MQ（当前变更频率极低，不值得） |
