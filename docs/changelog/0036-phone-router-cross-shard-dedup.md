# 0036 跨分片查重：手机号路由表 user_phone_router

## 背景：一个「现在能忍、扩容就炸」的问题

`user` 表分片键是 `id`，但注册去重、登录定位的查询条件是 `phone`——**不带分片键**，
ShardingSphere 只能广播到全部物理表再合并结果：

```java
// 改造前
SELECT COUNT(*) FROM user WHERE phone = ?        // 广播 4 片后合并 COUNT
SELECT ... FROM user WHERE phone = ?             // 广播 4 片后取首行
```

4 分片时尚可接受，但**分片数一扩（规划中的 128）就是 128 次跨库查询**——
一次登录打 128 个库，注册去重退化成 128 次 COUNT 合并。这个问题必须在扩容前解决，
否则扩容本身就会把登录/注册打挂。代码注释里早就写着「正解是 user_phone_router 路由表，
demo 阶段不引入」，本次即把它落地。

## 方案：phone → uid 路由表

新增单表 `user_phone_router`（`PRIMARY KEY(phone)`，落 ds_0），把两次查询改写成
「先查路由拿 uid → 再按 `id` 精准命中 user 的单个分片」：

```
注册去重：SELECT user_id FROM user_phone_router WHERE phone = ?   （单表 PK 点查）
登录定位：同上 → SELECT ... FROM user WHERE id = ?                 （带分片键，单分片）
```

### 迁移期安全设计（关键）

**路由表没有全量数据时，不能导致误判为「未注册」**，否则就是一次需要停机的在线索引迁移。
`UserJdbcRepository#resolveUserIdByPhone` 采用三级策略：

1. 路由命中 → 直接用；
2. 路由未命中（存量未回填 / 绑定补偿未完成）→ **回退广播查询**，正确性优先；
3. 广播命中 → 说明是历史用户，**顺手 `INSERT IGNORE` 回填**，下次同一手机号即走路由。

因此「回填没跑完」的最坏后果只是暂时退回旧的广播成本，绝不是功能错误。
存量回填语句（可重复执行）已写进 `init-local.sql` 的建表注释里。

### 附带收益：唯一一处真正 enforce 手机号全局唯一

`user` 表的 `uk_phone` **只在本分片内唯一**，跨分片重复它拦不住。
路由表用 `INSERT IGNORE` 绑定，主键冲突即「已被占用」——
并发注册同一手机号时收敛为一个成功、一个被拒（注册流程里的并发闸门），
失败/崩溃路径由 `unbind` 与登录侧自愈兜底。

### 为什么是单表而不是按 phone 分片（权衡记录）

- 注册/登录 QPS 比上传、Feed 读取低几个数量级，单表 PK 点查远未到瓶颈；
- 唯一性由**一处**主键保证，而不是「每片各自唯一」——后者依赖
  「同一 phone 必落同一片」这一前提，分片算法或 sharding-count 一变就可能失效；
- 存量回填 / 人工订正只需一条同实例跨库 `INSERT ... SELECT`，运维成本最低。

真到瓶颈时的演进路径（已实证可行）：按 `phone` 挂 autoTables + HASH_MOD。
`HashModShardingAlgorithm` 字节码为 `Math.abs(value.hashCode()) % shardingCount`
（走 `Object.hashCode()`），**字符串分片键可用**；或直接迁到 Redis / 分布式 KV。

## ⚠️ 顺带修掉一个潜伏已久的线上级 Bug：种子数据落错分片

端到端验证时首次登录直接返回「账号未注册」，顺着 ShardingSphere 的 SQL 日志查到根因：

```
Actual SQL: ds_0 ::: SELECT user_id FROM user_phone_router WHERE phone = ? ::: [13800138000]
Actual SQL: ds_1 ::: SELECT ... FROM user_1 WHERE id = ? ::: [1000000000000000000]
```

`init-local.sql` 里写的是 `id 1000000000000000000 % 4 = 0 → user_0(ds_0)`，
**但 HASH_MOD 不是 `id % 4`**，而是 `abs(Long.hashCode(id)) % 4`：

| id | id % 4（错） | hashCode | abs%4（真） | 正确落位 |
|---|---|---|---|---|
| 1000000000000000000 | 0 → user_0 | -1434143053 | **1** | turbo_feed_2.user_1 |
| 1000000000000000001 | 1 → user_1 | -1434143054 | **2** | turbo_feed_1.user_2 |
| 1000000000000000002 | 2 → user_2 | -1434143055 | **3** | turbo_feed_2.user_3 |

三个演示账号**全插错了库**。改造前登录能成功，纯粹是因为按 phone **广播会扫全部物理表**，
恰好把错位的行扫出来了——一旦改成按 id 精准路由就永远查不到。
已修正脚本落位 + 注释（并写明以后加种子必须按 `abs(hashCode)%4` 算），本机数据也做了订正。

### 由此加固的一处逻辑

`findByPhone` 原本在「路由命中但按 uid 查不到 user 行」时直接 `unbind`。
这在「数据错位」场景下是**灾难**：删掉映射 → 下次广播又命中 → 又删 → 账号永久不可用。
改为**先用广播二次确认**：广播命中即按实际行返回并 WARN（定位为数据错位，不中断登录）；
广播也没有才判定为「注册崩溃残留的孤儿映射」并清理。

## 验证（真机，非推断）

MQ profile 启动后真实 HTTP 登录三个演示账号，ShardingSphere SQL 日志：

```
13800138000 → ds_0 user_phone_router → ds_1 ::: user_1 WHERE id = ?   ✅ 单分片
13900139000 → ds_0 user_phone_router → ds_0 ::: user_2 WHERE id = ?   ✅ 单分片
13700137000 → ds_0 user_phone_router → ds_1 ::: user_3 WHERE id = ?   ✅ 单分片
```

**没有任何一条 `FROM user WHERE phone = ?` 的广播 SQL**。三个账号均返回 200 + JWT。

## 改动清单

- 新增 `UserPhoneRouterRepository`（findUserId / bindIfAbsent / unbind）
- `UserJdbcRepository`：路由优先 + 广播兜底 + 惰性回填；新增 `findById`（带分片键）
- `UserService#register`：改为「先绑定路由（并发闸门）→ 落库 → 失败补偿 unbind」
- `init-local.sql`：建表 + 回填语句 + 种子路由行 + **修正三个演示账号的物理落位**
- `shardingsphere-config.yaml`：`!SINGLE` 登记 `ds_0.user_phone_router`
- 被修改的 Lombok 类（`UserJdbcRepository` / `UserService`）改显式构造器（本机环境坑）

## 后续

- 线上存量数据需执行一次回填（语句已在 SQL 注释里）；不回填也能跑，只是走广播兜底。
- 分片数扩容到 128 前，本表是单点（注册/登录 QPS 级别，预计长期够用）；
  真到瓶颈再按 phone 分片或迁 Redis。
