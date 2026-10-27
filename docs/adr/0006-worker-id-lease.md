# ADR-0006：雪花 worker-id 自租约

- **状态**：已采纳（Accepted，2026-10-27）
- **所属改进项**：`improvement-plan.md` §3 / M2-4.3（原属 P0-5 Nacos 的 phase-2，本次独立落地）
- **决策人**：dafei
- **关联 ADR**：[0002](0002-nacos-service-discovery.md)（该 ADR §6.3 把「worker-id 集中分配」回退到 phase-2，本 ADR 是它的兑现方式，但换了实现路径）

## 1. 背景（Context）

雪花 ID 的 `workerId` 只有 5 位（0~31）。两个实例拿到同一个 `workerId`，同一毫秒就会生成
**完全相同的 ID** → 主键冲突。

改造前的做法是**人手分配**：`TURBOFEED_SNOWFLAKE_WORKER_ID` 环境变量由部署者填写，
缺失时 `requireInstanceId` 直接启动失败。

这条防线在三种场景下失效：

1. **容器化扩缩容**：`docker compose up --scale gateway=3` 三个容器读同一份 `.env`，撞号；
2. **K8s Deployment**：所有 Pod 共享同一个环境变量来源；
3. **复制粘贴**：新增一台机器时照着上一台的配置抄，改漏一个字段。

三种场景的共同点是：**错误发生在部署期，暴露在运行期，且暴露方式是主键冲突**——
一条已经写了一半的事务报 `DuplicateKeyException`，排查的人第一反应会去查业务代码。

ADR-0002 原本计划由 Nacos 统一分配，但那条路径需要「Nacos 注册表读实例序号」，
本机没有 Nacos 环境无法验证，被回退为 phase-2（见该 ADR §6.3）。

## 2. 决策（Decision）

**用 Redis 做「带续约的租约」**，把 worker-id 从「人填的静态配置」变成「运行时自取的租约」：

1. **抢占**：`SET tf:snowflake:lease:{dc}:{workerId} <instanceId> NX EX <ttl>`，
   从随机起点 0~31 顺延尝试；抢到的即为本实例的 `workerId`。
2. **续约**：独立 daemon 线程池（`tf-snowflake-lease-renew`，非 `@Scheduled`）按
   `renew-interval` 周期执行 Lua 脚本：
   ```lua
   if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('EXPIRE', KEYS[1], ARGV[2]) return 1 else return 0 end
   ```
   **比对 instanceId 再续期**：不比对就会替别人续租——实例 A 卡顿超过 TTL、租约被 B 抢走后，
   恢复的 A 会把 B 的租约续上，于是两个实例同时认为自己是同一个 `workerId`。
3. **释放**：`destroyMethod = "release"`，同样走 Lua 比对后 `DEL`，绝不删别人的租约。
4. **失效即拉闸（fail-closed）**：`SnowflakeIdGenerator` 持有一个 `IdLease`，
   `nextId()` 开头检查 `isHeld()`；续约失败 → 拒绝发号并抛异常。
5. **启动期校验**：续约间隔必须 ≤ TTL 的一半，否则**启动失败**（配置错了不该等到运行时才发现）。
6. **开关**：`turbofeed.snowflake.lease-enabled`，默认 `false`（保持原显式配置路径）。

## 3. 后果（Consequences）

**正面**
- 扩缩容不需要人介入，撞号从「部署期人为错误」变成「结构上不可能」；
- 续约失败即停发号，比「继续发号然后撞主键」安全得多——前者是明确的、可告警的故障，
  后者是数据层的隐式损坏。

**负面 / 成本**
- **引入启动期对 Redis 的硬依赖**：租约模式下 Redis 不可用则网关起不来。
  这是**刻意**的——「退化为显式配置」看起来更健壮，实际会让「Redis 短暂抖动」变成
  「悄悄用了一个可能撞号的 workerId」，把可用性问题换成正确性问题。
- **TTL 窗口内仍有风险**：实例 A 长时间 GC 停顿 / 网络分区超过 TTL，租约会过期被 B 抢走；
  A 恢复后若还没意识到（续约线程也没跑成功）仍可能发号。缓解措施是 `IdLease` 的拉闸，
  但它依赖「续约线程先失败」这个前提。彻底解决需要 fencing token（ID 里带租约代号），
  代价是改 ID 结构，不做。
- 多了一次 Redis 往返的续约（每实例每 20s 一次），可忽略。
- **`lease-enabled=true` 的真启动未验证**：本机 6379 未监听，只有单测覆盖
  （`WorkerIdLeaseManagerTest` 15 例 / `SnowflakeConfigTest` 4 例，含装配互斥）。

## 4. 备选方案

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 继续人填环境变量 | **否决** | 三种常见部署方式全部失效，且失效方式隐蔽（运行期主键冲突） |
| Nacos 注册表 / 数据库号段分配 | **暂缓** | 本机无 Nacos 无法验证；号段模式多一张表 + 一套回收逻辑，收益与 Redis 租约相当 |
| K8s StatefulSet 序号派生 | **暂缓** | 只解决 K8s 一种部署形态，本项目的 compose / 裸机场景仍会撞 |
| 租约失效后「退化为显式配置」 | **否决** | 把可用性问题换成正确性问题，见上 |
| 不用雪花，改用 UUID / 数据库自增 | **否决** | 前者丢失有序性（时间线 ZSET 依赖 ID 有序），后者成为全局写热点 |

## 5. 落地记录（2026-10-27）

- **已落地**：`IdLease`（函数式接口，让生成器不认识 Spring/Redis）、`WorkerIdLeaseManager`、
  `SnowflakeConfig` 拆成两个互斥 `@Bean`（`@ConditionalOnProperty` 按字面量匹配，
  专门写了 `SnowflakeConfigTest` 验装配互斥）、配置项三个。
- **一个刻意的类设计**：`WorkerIdLeaseManager` 上**不加 `@Component`**——抢占必须发生在
  `@Bean` 方法体内（`manager.acquire()`），类上再加注解会与方法级 `@Bean` 同名重复注册。
- **未做**：`lease-enabled=true` 的真启动（缺 Redis）。这是本 ADR 唯一没有端到端验证的部分。
