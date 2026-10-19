# ADR-003：落地 tf-counter 计数服务（P1-2，提前到 P0 之后）

- **状态**：已采纳（Accepted，2026-10-17）
- **所属改进项**：`improvement-plan.md` §3 / P1-2（用户决策：提前到 P0 之后即做）
- **决策人**：dafei
- **关联 ADR**：ADR-001（测试）、ADR-002（Nacos，多实例计数依赖服务发现）

## 1. 背景（Context）

当前点赞 / 评论计数在 `tf-gateway` **直写 MySQL**（分片到 128 表）：`UPDATE media SET like_count = like_count + 1`。

- 高并发下**热点行锁竞争**（单条爆款内容的 like_count 行被大量 UPDATE 争抢）；
- 它同时是**推荐实时特征（热度分）的数据底座**——直写 MySQL 在压测下会先爆，拖慢排序；
- `tf-counter` 模块目前**只有空壳启动类**（`CounterApplication`），未实现。

用户决策：P0-1 测试 + P0-5 Nacos 是"敢上生产"两道坎，**P1-2 tf-counter 提前到 P0 之后就做**——它是推荐实时特征的数据底座，优先级实质上已到 P0。

## 2. 决策（Decision）

落地 `tf-counter`（Redis 分桶 + MQ 削峰 + 批量落库 + L1 Caffeine 本地缓存）：

1. **写入路径**：点赞 / 评论接口只写 Redis（按 `media_id` 取模分 64 桶 `INCR`），**不直写 MySQL**；经 MQ 削峰，后台批量 `UPDATE` 落库。
2. **读取路径**：排序模型读热度分优先 L1 Caffeine → Redis →（miss）MySQL；最终一致可接受（计数短暂偏差无碍推荐）。
3. **可靠性**：批量落库失败可重试；Redis 不可用 fail-open 回退读 MySQL（不阻断主流程）。
4. **热点治理**：64 桶打散单热点行，消除热点行锁；与 `tf-hotspot`（L1 + 广播失效）思路一致。
5. **计分接口**：`tf-counter` 暴露热度分查询接口，供 `tf-feed-engine` 排序模型消费（走 ADR-002 服务发现寻址）。

## 3. 后果（Consequences）

**正面**
- 点赞 / 评论 1000 QPS 下 MySQL 无热点行锁（看慢查询日志验证）；
- 热度分实时可用，推荐排序特征不再受写库拖累；
- 计数与写路径解耦，主流程更稳。

**负面 / 成本**
- 引入最终一致语义：计数短时间内可能与 MySQL 不一致（推荐可接受）；
- 多一跳依赖（Redis + MQ + tf-counter 服务），需消费端幂等（at-least-once）；
- 多实例部署依赖 ADR-002 Nacos 做服务发现。

## 4. 备选方案（Alternatives Considered）

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 纯 Redis 不落库 | **否决** | 重启 / 淘汰策略导致计数丢失，审计与推荐底座不可信 |
| 直写 MySQL + 乐观锁（version 字段） | **否决** | 热点行仍竞争，乐观锁重试放大写放大，压测先爆 |
| Redis Lua 原子 INCR + 定时落库（不分桶） | **折中但不足** | 单 key 仍热点，分桶才能打散；落库仍必要 → 采用本 ADR 的分桶 + MQ + 批量落库 |
| 数据库号段（segment）预分配计数 | **暂缓** | 适合"全局单调 ID"，不适合"按内容实时计数"，语义不符 |

## 5. 落地顺序

1. ADR-001（测试）完成 → 2. ADR-002（Nacos）完成 → 3. 本 ADR（tf-counter）：先 Redis 分桶写 + MQ 削峰 + 批量落库，再接排序模型热度分消费。

## 6. 分布式 flush 与幂等（ShedLock + 共享 Redis 缓冲 + batch_id 去重）

> 2026-10-19 增强。回答「`CounterFlushScheduler.flush()` 在分布式环境怎么只执行一次、数据量大怎么保证可行性」这两个疑问，并说明本设计为什么能达到效果。

### 6.1 问题拆解

- **分布式只执行一次**：多 `tf-counter` 副本同时跑 `@Scheduled` drain。若缓冲仍是各副本进程内 `ConcurrentHashMap`，选主后只有 leader 落库 → 非 leader 副本的缓冲永不被 flush（计数丢失）；若不选举、多副本各自 drain 同一份**共享**缓冲 → 重复落库。二者必居其一，必须显式解决。
- **大数据量可行性**：原 flush 逐帖串行 `UPDATE + INSERT`、再逐帖串行 `SELECT` 回读，DB 往返 O(P)；缓冲无界有单实例堆 OOM 风险；串行 `@Scheduled` 线程是瓶颈。

### 6.2 决策：双模式调度（缓冲与锁强耦合）

- **standalone（默认 `redis-buffer.enabled=false`）**：缓冲 = 进程内 `CounterBufferService`（`ConcurrentHashMap`）。每个副本天然分片、各 flush 自己那一份，**无需也不该加锁**——加锁反而丢非 leader 缓冲。零 Redis 依赖、零锁开销。
- **clustered（`redis-buffer.enabled=true`，且必须同时 `redis.enabled=true`）**：缓冲切换为共享 `RedisCounterBuffer`（Redisson Lua 原子 `add`/`drain`）；`ClusteredCounterFlushScheduler` 用 `@SchedulerLock(name="tf-counter-flush")` 选主，多副本仅 leader drain。

> ShedLock 的 `@EnableSchedulerLock` 与 `JdbcLockProvider` 同样以 `redis-buffer.enabled=true` 为门控（`ShedLockConfig`），与集群调度器同生共灭——从机制上杜绝「开了锁却没共享缓冲」的丢数组合。

### 6.3 为什么能达到「不丢、不重、只执行一次」

1. **选主保证唯一 drainer**：`JdbcLockProvider` 复用 tf-counter 自有 DataSource 在 `shedlock` 表加行锁；`lockAtMostFor=9s` 兜底任务卡死（必释放让其他副本接管），`lockAtLeastFor=4s` 防同副本被调度框架连续抢到导致其他副本饿死。同一时刻只有一个副本 drain。
2. **共享缓冲保证不丢**：所有副本增量写同一份 Redis 缓冲（Lua `SADD`+`INCRBY` 原子），非 leader 的增量也已入共享缓冲，由 leader 统一落库，不存在「某副本私藏增量」。
3. **drain 原子保证不重**：`RedisCounterBuffer.drain()` 的 Lua 脚本 `SMEMBERS→读→DEL+SREM` 整段原子，Redis 保证脚本执行期间无其他命令穿插——既不会丢 drain 过程中新到达的 `add`，也不会把同一份增量交给两个 drainer（ShedLock 已确保只有一个）。
4. **batch_id 去重兜底幂等**：`applyBatch` 每周期生成 UUID `batch_id`，先 `INSERT counter_flush_dedup(batch_id, post_id)` 登记；同一 `(batch_id, post_id)` 再次到达（`DuplicateKeyException`）直接跳过 UPSERT，**绝不重复累加**。不同批次的同帖各自合法落库（batch_id 不同，去重行不冲突）。整批重放自动降级为逐帖 `safeApply` 补落，部分已落/部分未落的故障恢复也安全。

### 6.4 数据量可行性（对应「大数据量怎么保证可行性」）

- 落库由「逐帖串行 UPDATE + 逐帖串行 SELECT」改为 **批量 UPSERT**（`INSERT ... ON DUPLICATE KEY UPDATE ... + VALUES(...)`，O(1) 往返）+ **批量回读**（`SELECT ... WHERE post_id IN (...)`，O(1) 往返），DB 往返与 P 解耦（原 O(P) → 2 次）。
- 缓冲由「进程内无界 Map」升级为 **共享 Redis**（天然有界、按 key 淘汰），消除单实例堆 OOM 风险。
- `CounterFlushService` 另含低频 `purgeStaleDedup`（默认 1h TTL）清理过期去重记录，表不无限增长。

### 6.5 验证状态

- `tf-counter` `mvn compile` + `mvn test` 通过；H2（MySQL 模式）集成测试覆盖：① `applyBatch` 同 batch_id 重放计数不变（幂等）② 不同 batch_id 同帖各落（不误拦）③ `getMany` 批量回读 ④ ShedLock `JdbcLockProvider` 在 H2 可加锁/解锁——全部绿。
- Redis 真链路（共享缓冲 drain + ShedLock 选主 e2e）代码已接线+编译，但**需本机 Redis 启动**才能跑（本地沙箱无 Redis/Docker，未执行真链路）。
