# 0039 账号状态本地缓存（信用等级 / 可写闸门）

## 问题：把「低频变更」的数据按「每请求一次 DB」在读

两项状态都挂在**写热路径**上，每次上传必读：

| 调用 | 改造前每次上传的成本 |
|---|---|
| `AccountCreditService#ensure` | 一次 `INSERT ... ON DUPLICATE KEY` + 一次 `SELECT` |
| `PenaltyService#canWrite` | 一次 `SELECT` |

而它们读的是**账号级、低频变更**数据。单实例 2000 QPS 上传就是 4000 次/秒的纯重复读，
白白吃掉连接池与分片库的 CPU / 行锁。

## 方案：`AccountStateCache`（进程内、短 TTL）

- **缓存命中即连 INSERT 一起跳过**：命中说明该账号此前已 `ensure` 过（行必然存在），
  再补一次 `ON DUPLICATE KEY` 纯属浪费。这是本次最大的一笔收益——
  **上传热路径上原本每帖必有一次写**。
- **所有写路径主动失效**：扣分 / 恢复 / 观察期计数 / 记录违规 / 解除封禁 都调 `invalidate`，
  不存在「自己改了自己看不见」。
- **零三方依赖**：只用到「TTL + 上限」，`ConcurrentHashMap` 足够。
  不引 Caffeine 是刻意的——它会带进驱逐线程、权重、统计一整套配置面，
  而这里恰恰要的是**行为可预测**。

### 为什么是本地缓存而不是 Redis

Redis 能解决多实例一致性，但把一次「本可不发生的读」换成一次「必然发生的网络往返」，
对**降低 DB 压力**这个目标反而更贵。正确组合是
「本地缓存抗读热点 + Redis 广播失效保证一致性」——本次先做前者（收益确定、零依赖）。

## 已知边界（必须知道，否则会误用）

1. **跨实例延迟**：A 实例封禁某账号，B 实例最长 `ttl-seconds`（默认 15s）后才失效，
   期间该账号在 B 上仍可写。封禁是人工低频操作，15s 漏放窗口可接受；
   要「立即生效」时用 Redis pub/sub 广播 `invalidate` 补齐（入口已留）。
2. **超限整体清空**：条目数超过 `max-entries` 时直接 `clear()`（不是 LRU）。
   这是保护性熔断：宁可缓存整体失效退回全量读 DB，也不让 map 无界增长拖垮堆。
3. **可一键关闭**：`turbofeed.state-cache.enabled=false` 即退回直读，
   用于「怀疑缓存导致状态不更新」时的快速回滚（与内容安全总开关同手法）。

## 配置

```yaml
turbofeed.state-cache:
  enabled:      ${TURBOFEED_STATE_CACHE_ENABLED:true}
  ttl-seconds:  ${TURBOFEED_STATE_CACHE_TTL_SECONDS:15}
  max-entries:  ${TURBOFEED_STATE_CACHE_MAX_ENTRIES:100000}
```

## 改动清单

- 新增 `service/state/AccountStateCache`（两类型安全缓存：CreditLevel / Boolean）
- `AccountCreditService`：ensure / getLevel 走缓存；onHumanApproved / onViolationConfirmed /
  onAppealUpheld 主动失效
- `PenaltyService`：`canWrite` 走缓存；`recordViolation` / `liftPenalty` 主动失效
- `application.yml`：新增 `turbofeed.state-cache.*`
- 两个被修改的类改显式构造器 + 显式 Logger（本机 Lombok 坑）

## 验证

MQ profile 启动成功，三个演示账号登录正常（登录链路会经 `canWrite` 闸门），
无缓存相关异常。缓存本身为纯内存逻辑，其收益体现在上传热路径的 DB 读次数上，
未做压测量化——需要时用 ShardingSphere 的 `sql-show` 对比开关缓存前后的 SQL 条数即可。
