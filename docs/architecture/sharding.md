# 分库分表设计（turbo-feed-gateway 元数据层）

> 载体：ShardingSphere JDBC 5.5.3（原生驱动模式）+ **内置 HASH_MOD 算法**。
> 适用：网关元数据表（首张为 `user`）。其他服务（counter / feed-engine）如需分片再评估是否上提至 `tf-shared`。

## 1. 为什么分片

UGC 场景元数据增速 ≈ 用户量 × 人均写入量。单表在千万级开始遇到 B+ 树高度、备份恢复、DDL 锁表等瓶颈。turbo-feed 起步单库单表即可，**当 `user` 等核心表逼近千万行、或单实例写入/容量成为瓶颈时**再引入分片，过早分片是过度设计（与 MediaController 设计要点第 4 点一致）。分片是"容量与写入线性扩展"手段，不是"查询加速"手段——跨分片聚合查询反而更慢。

## 2. 分片键选择

- **选 `id`（用户全局唯一 ID，雪花 Long）作为分片键**。理由：
  1. 它是主键，所有单行操作（按 id 查/改/删）都能精准命中单分片，无需广播；
  2. 雪花 ID 高位是时间、低位含序列，整体分布均匀，`hash(id) % N` 分布好；
  3. 与服务内身份体系一致（JWT 携带的 `userId` 即此 `id`），登录/鉴权路径天然带分片键；
- **刻意不用 `username` 做分片键**：登录常只给 username，按 username 分片会要求先定位 id（鸡生蛋），且 username 可变。username 仅作展示与单分片内唯一，全局唯一由上层保证（见 §5）。

## 3. 分片方案（当前）

```
逻辑表 user
  ├─ 分片键 id（Long，雪花）
  ├─ 分库：id % 2  → ds_0(turbo_feed_1) / ds_1(turbo_feed_2)
  └─ 分表：id % 2  → user_0 / user_1
物理表（4 张）：turbo_feed_1.user_0 | turbo_feed_1.user_1 | turbo_feed_2.user_0 | turbo_feed_2.user_1
```

选择 2×2 的考量：起步规模小，2 库解决单实例容量上限、2 表解决单表行数上限，足以撑到亿级；且 2 的幂次取模无余数偏差。**算法 = Apache ShardingSphere 5.5.3 内置 `HASH_MOD`**，由 `sharding-count` 显式指定候选数；扩容时 `sharding-count` 与 `actualDataNodes` 同步调整。

## 4. 算法选型（SS 内置 HASH_MOD）

**为什么用 SS 内置而不是自写类**：

| 维度 | 自写算法 | SS 内置 HASH_MOD |
|---|---|---|
| 接口契约正确性 | `StandardShardingAlgorithm` 在 5.5.x 无 `getProps()` 方法，自写极易踩到 | SS 官方维护，签名与接口演进同步 |
| `DataNodeInfo` 拼接逻辑 | 需自己组装 `prefix + paddedIndex` | `ShardingAutoTableAlgorithmUtils.findMatchedTargetName` 内部实现 |
| 兼容性 | 跨 SS 升级需跟进接口调整 | 与 SS 版本绑定一致 |
| 评审负担 | 新增代码需 Code Review 算法正确性 | 引入成熟实现即可 |

**内置算法工作方式**（`org.apache.shardingsphere.sharding.algorithm.sharding.mod.HashModShardingAlgorithm`）：

1. `init(props)` 从 `sharding-count` 读分片数；
2. `doSharding(precise)` 计算 `Math.floorMod(value, shardingCount)` 取下标，再经 `ShardingAutoTableAlgorithmUtils.findMatchedTargetName` 把下标拼成 `prefix + padded(value)`（默认 `paddingChar='0'`、`suffixMinLength=1`），最后用 `Collection.contains` 在 `availableTargetNames` 内命中目标；
3. `doSharding(range)` 直接返回全部 `availableTargetNames`（取模破坏键序，无法收敛范围）——由 ShardingSphere 合并各分片结果返回。

**实际约定**：`actualDataNodes` 必须写成 `prefix${0..N-1}` 形式与内置前缀匹配工具对接，例如 `ds_${0..1}.user_${0..1}`，下标右对齐、`0` 填充。

> 早先版本曾试图自写一份 `HashModShardingAlgorithm`，但 5.5.0 接口里 `getProps()` 方法并不存在（仅 `TypedSPI.default init(Properties)` 与 `getType()`），编译 `@Override` 必然报红且功能与内置完全重合——已删除自写类，统一改用 SS 内置。详见 `docs/changelog/0010`、`docs/changelog/0012`。

## 5. 跨分片唯一性（必读坑）

`uk_phone` 唯一索引只在**单张物理表**内生效，ShardingSphere 不跨分片去重。两个不同 id 的用户注册相同手机号且落在不同分片时，各自唯一约束成立都能插入，出现跨分片重复手机号。应对（对标抖音账号体系）：

1. **推荐**：注册先写一张**不分片的路由表** `user_phone_router(phone_hash → user_id + 分片号)` 做全局去重与定位，再写分片表；登录也先经路由表用手机号定位 id；
2. 登录统一走 `id`（JWT 携带的 uid），手机号仅作登录凭证与单分片内唯一，全局唯一由路由表保证；
3. 应用层注册接口对手机号加分布式锁 / 全分片扫描查重（成本高，仅作兜底）。

> 当前 `user_schema.sql` 保留 `uk_phone` 作为单分片内防护，并新增不分片的 `user_phone_router` 路由表承担全局唯一与 phone→uid 定位（见 changelog 0013）。

## 6. 扩容路径

| 阶段 | 动作 | 影响 |
|---|---|---|
| 当前 | 2 库 × 2 表 | 撑到约亿级 |
| 单表行数瓶颈 | 2 库 × 8 表（`user_${0..7}`） | 同步 `shardingAlgorithms.userTableSharding.props.sharding-count: 8` + 建 6 张新表 |
| 单库容量瓶颈 | 4 库 × 8 表 | 加 ds_2/ds_3 数据源 + 库分片 `sharding-count: 4` + 建表 |
| 扩容再平衡 | 一致性哈希 / 双写迁移 | `id % N` 扩容（N 变化）会大面积改变路由，必须**数据迁移 + 灰度双写**。若预期终态分片数较大，应尽早确定，或在算法层预留一致性哈希以减小迁移面 |

## 7. 接入与配置

- `application.yml`：`spring.datasource.driver-class-name=org.apache.shardingsphere.driver.ShardingSphereDriver` + `url=jdbc:shardingsphere:classpath:shardingsphere-config.yaml`；
- `shardingsphere-config.yaml`：定义 `dataSources`（ds_0/ds_1，生产账号用密钥管理注入）+ `rules.!SHARDING`（user 表规则 + 两个内置 `HASH_MOD` 算法，`sharding-count` 显式控制候选数）+ 雪花主键生成器；
- `pom.xml`：`shardingsphere-jdbc:5.5.3`（原生驱动，无需 starter；⚠️ 注意是 `shardingsphere-jdbc`，**不是** `shardingsphere-jdbc-core`，后者不存在导致 import 全军报红）。**版本选择**：5.5.3 是 5.5.x 线最新稳定版（2026-02-23 发布），相比 5.5.0 已修复"test-util 缺失发布"的 release 缺陷（Apache PR #31143），无需 exclusion。

## 8. 账号体系对标抖音（设计原则）

turbo-feed 直接对标抖音架构，账号体系遵循抖音同款分层：

| 层 | 标识 | 在本项目 | 说明 |
|---|---|---|---|
| 凭证层 | 手机号 | `AuthController.login(phone, password)` | 注册/登录入口；demo 阶段用密码，抖音以验证码为主 |
| 身份层 | UID（雪花 Long） | `user.id` + JWT `sub` | 系统内部锚点、分片键、令牌载荷 |
| 路由层 | phone→uid | `user_phone_router`（不分片） | 登录入口用手机号定位 uid |
| 脱敏层 | sec_uid | 暂未实现 | 未来对外暴露用脱敏标识防枚举 |

**核心约定**：

- 分片键固定为 `id`（UID），**手机号不参与分片计算**（与抖音「UserID 作为单元化分区维度」一致）；
- 令牌 `sub` 携带 **UID 而非手机号**，手机号只在登录入口起作用；连表后业务按 uid 精准命中分片、不发生错位；
- 扩容加表只与数据量相关、与"改手机号登录"无关（见 §6）。

> 演进记录：changelog 0013（登录账号 username→phone + 建 user_phone_router）、changelog 0014（令牌 sub 由手机号改为 UID，完成与抖音的对齐）。