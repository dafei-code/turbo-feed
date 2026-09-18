# 分库分表设计（turbo-feed-gateway 元数据层）

> 载体：ShardingSphere JDBC 5.5.3（原生驱动模式）+ **内置 HASH_MOD 算法** + `autoTables` 自动分片。
> 适用：网关全部元数据表（当前 6 张逻辑表：`user` / `media` / `account_credit` / `report` / `appeal` / `comment`）。
> 其他服务（counter / feed-engine）不持有元数据表，不参与分片。

## 1. 为什么分片

UGC 场景元数据增速 ≈ 用户量 × 人均写入量。单表在千万级开始遇到 B+ 树高度、备份恢复、DDL 锁表等瓶颈。turbo-feed 起步单库单表即可，**当核心表逼近千万行、或单实例写入/容量成为瓶颈时**再引入分片，过早分片是过度设计（与 MediaController 设计要点第 4 点一致）。分片是"容量与写入线性扩展"手段，不是"查询加速"手段——跨分片聚合查询反而更慢。

## 2. 分片键选择

**总原则：分片键必须是该表最高频访问路径上「必然携带」的列**，否则查询会退化为全分片广播。

| 逻辑表 | 分片键 | 为什么是它 |
|---|---|---|
| `user` | `id`（雪花 Long） | 它是主键，单行操作（按 id 查/改/删）精准命中单分片；雪花高位是时间、低位含序列，整体分布均匀，`hash(id) % N` 分布好；与服务内身份体系一致（JWT 的 `userId` 即此 `id`） |
| `media` | `user_id` | 「我的上传」`WHERE user_id = ?` 是唯一高频路径，**精准命中单分片**；且与 `user` 同片（同用户的行同库同表），便于按用户聚合 |
| `account_credit` | `user_id` | 与 `user` 同片；`ensure` / `deduct` / `restore` / `onHumanApproved` 全部 `WHERE user_id = ?` 单分片命中，信用扣减不跨片。加列须**逐张物理表**执行（`account_credit_0.._3` 共 4 张，分落两库） |
| `report` | `media_id` | 举报的写入与处理（`insert(media_id)` / `resolve(WHERE media_id=?)`）均按 `media_id` 定位；管理员待处理列表按 `status` 查为**广播**，低频可接受 |
| `appeal` | `media_id` | 同 `report`，申诉单与举报单按同一维度聚集，便于按内容聚合查看 |
| `comment` | `media_id` | 「读某条内容下所有评论」单分片命中，**不依赖 media 所在库**即可定位评论——评论分片键与 media 分片键（`user_id`）**正交**，这是刻意的：评论独立成包、独立读写路径，不与内容耦合 |

**刻意不用 `username` 做 `user` 表分片键**：登录常只给 username/phone，按 username 分片会要求先定位 id（鸡生蛋），且 username 可变。username 仅作展示与单分片内唯一，全局唯一由上层保证（见 §5）。

## 3. 分片方案（当前）

```
规则：autoTables + HASH_MOD，sharding-count = 4（= 库数 2 × 表数 2）

逻辑表 → 物理表命名：<逻辑表>_0 .. <逻辑表>_3
落库规则：第 i 张物理表落在 actualDataSources.get(i % 2)
            ds_0(turbo_feed_1) ← 偶数下标 _0, _2
            ds_1(turbo_feed_2) ← 奇数下标 _1, _3

物理表（每张逻辑表 4 张，6 张逻辑表共 24 张）：
  turbo_feed_1：user_0 user_2 | media_0 media_2 | account_credit_0 account_credit_2
                report_0 report_2 | appeal_0 appeal_2 | comment_0 comment_2
  turbo_feed_2：user_1 user_3 | media_1 media_3 | account_credit_1 account_credit_3
                report_1 report_3 | appeal_1 appeal_3 | comment_1 comment_3
```

选择 2×2 的考量：起步规模小，2 库解决单实例容量上限、2 表解决单表行数上限，足以撑到亿级；且 2 的幂次取模无余数偏差。

> ⚠️ **物理表命名必须与 SS 的推断一致**：不是「每库各建 `_0`/`_1` 两份」，而是全局连续编号 `_0.._3` 后再按 `i % 库数` 摊到库上。若按前者建表，会报 `Table doesn't exist` 或路由错乱。建表脚本见 `deploy/mysql/init-local.sql`（其头部已显式说明"请勿改成 user_0/user_1 各库两份"）。

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

**关键约束（踩过的坑）**：

- **必须用 `autoTables` 而非 `tables`**。SS 5.5.x 把 `HASH_MOD` / `MOD` 归类为**自动分片算法**，只能挂 `autoTables`；写成手动 `tables` 会直接报 `tables sharding configuration can not use auto sharding algorithm`。
- **`standard` 策略只接受 `shardingAlgorithmName` 引用**，不接受内联 `shardingAlgorithm` 块。
- **`sharding-count` 是显式声明的候选目标数**，不是按 `availableTargetNames.size()` 自动适配——扩容时必须同步改它，否则分母失配。
- **`actualDataNodes` / 命名必须写成 `prefix${0..N-1}` 形式**与内置前缀匹配工具对接，下标右对齐、`0` 填充。

> 早先版本曾试图自写一份 `HashModShardingAlgorithm`，但 5.5.0 接口里 `getProps()` 方法并不存在（仅 `TypedSPI.default init(Properties)` 与 `getType()`），编译 `@Override` 必然报红且功能与内置完全重合——已删除自写类，统一改用 SS 内置。详见 `docs/changelog/0010`、`docs/changelog/0012`。

## 5. 跨分片唯一性（必读坑）

`uk_phone` 唯一索引只在**单张物理表**内生效，ShardingSphere 不跨分片去重。两个不同 `id` 的用户注册相同手机号且落在不同分片时，各自唯一约束成立都能插入，出现跨分片重复手机号。

**当前工程的实际处理（重要，别按蓝图理解）**：

| 方案 | 状态 |
|---|---|
| 不分片路由表 `user_phone_router(phone_hash → user_id + 分片号)` 做全局去重与定位 | ❌ **未启用**。表结构只在 `tf-gateway/.../db/user_schema.sql` 里作为方案说明保留；`deploy/mysql/init-local.sql` 明确「本 demo 阶段未启用，此处不建」；`UserJdbcRepository` 类注释亦写明「本 demo 阶段不引入」 |
| 注册 / 登录时走**广播 COUNT** | ✅ **实际在用**。`UserJdbcRepository` 执行 `SELECT COUNT(*) FROM user WHERE phone = ?`（注册查重）与 `SELECT id, password_hash, status, role FROM user WHERE phone = ?`（登录定位），由 SS 自动广播到全部分片并归并 |
| 应用层加分布式锁 / 全分片扫描查重 | 仅作兜底思路，未实现 |

即：**当前是「单分片内 `uk_phone` 防护 + 跨分片广播 COUNT 查重」**，广播查询随分片数增长而线性变慢，是分片数扩大后必须先解决的点（见 §6 与 §8 演进）。

生产建议（供后续演进）：注册先写不分片路由表做全局去重与定位，再写分片表；登录也先经路由表用手机号定位 `id`；令牌统一携带 `id`，手机号仅作登录凭证。

## 6. 扩容路径

| 阶段 | 动作 | 状态 / 影响 |
|---|---|---|
| 当前 | 2 库 × 2 表（`sharding-count=4`） | ✅ **已落地**，撑到约亿级 |
| 媒体表大幅扩容 | 2 库 × 64 表 = **128 物理表** | ⚠️ **配置与建表脚本已就绪，未切换**：`tf-gateway/src/main/resources/shardingsphere-config-128.yaml` + `db/expand_media_tables_128.sql`。切换需改 `application.yml` 的 `spring.datasource.url` 指向 128 版配置 |
| 单库容量瓶颈 | 4 库 × N 表 | 加 `ds_2`/`ds_3` 数据源 + 库层 `sharding-count` + 建表 |
| 扩容再平衡 | 一致性哈希 / 双写迁移 | `hash % N` 扩容（N 变化）会**大面积改变路由**，必须**数据迁移 + 灰度双写**。因分片数变化需先做存量再平衡，**不可直接切换配置**，否则路由错乱 |

> 读路径说明：公域发现流已改走 Redis 时间线（不扫分片库），因此扩容主要服务**写入分布**与个人中心「我的上传」（按 `user_id` 精准单分片）。详见 `docs/changelog/0019`。

## 7. 接入与配置

- `application.yml`：`spring.datasource.driver-class-name=org.apache.shardingsphere.driver.ShardingSphereDriver` + `url=jdbc:shardingsphere:classpath:shardingsphere-config.yaml`；
- `shardingsphere-config.yaml`：
  - `dataSources`：`ds_0` / `ds_1`（Hikari + MySQL 驱动，指向 `turbo_feed_1` / `turbo_feed_2`）。**注意文件内账号密码仅供克隆即跑，生产必须用环境变量/密钥管理注入**（文件内已留 TODO）；
  - `rules - !SHARDING`：`autoTables`（6 张逻辑表的 `shardingColumn` 与算法引用）+ `shardingAlgorithms.hashMod`（`type: HASH_MOD` / `props.sharding-count: 4`）+ `keyGenerators.snowflake`；
  - `props.sql-show: true`（开发期打印实际落库表与 SQL，便于核对路由；生产可关）；
- `pom.xml`：`shardingsphere-jdbc:5.5.3`（原生驱动，无需 starter；⚠️ 是 `shardingsphere-jdbc`，**不是** `shardingsphere-jdbc-core`，后者不存在导致 import 全军报红）。**版本选择**：5.5.3 是 5.5.x 线最新稳定版，相比 5.5.0 已修复"test-util 缺失发布"的 release 缺陷（Apache PR #31143），无需 exclusion。
- **主键生成**：`user.id` 与 `comment.comment_id` 配置了雪花生成器；`media` / `report` / `appeal` 的业务主键由应用侧生成（如 `media/{userId}/{uuid}.{ext}`、`post/{userId}/{uuid}`），非 SS 生成。业务侧显式传 `id` 时以传入值为准。

> ⚠️ `deploy/mysql/init-local.sql` 顶部会 **DROP 两库再重建**，以保证 SS 元数据以最新 DDL（含 `phone` 列、`post_id`/`seq` 列）为准，避免 `CREATE TABLE IF NOT EXISTS` 跳过重建导致「列不存在」类陈旧元数据问题。生产环境**绝不可**直接执行。

## 8. 账号体系对标抖音（设计原则）

turbo-feed 直接对标抖音架构，账号体系遵循抖音同款分层：

| 层 | 标识 | 在本项目 | 状态 |
|---|---|---|---|
| 凭证层 | 手机号 | `AuthController.login(phone, password)` | ✅ 已落地；demo 阶段用密码，抖音以验证码为主 |
| 身份层 | UID（雪花 Long） | `user.id` + JWT `sub` | ✅ 已落地，系统内部锚点、分片键、令牌载荷 |
| 路由层 | phone→uid | `user_phone_router`（不分片） | ⚠️ **未启用**，当前用广播 COUNT 代替（见 §5） |
| 脱敏层 | sec_uid | 未实现 | 规划中，未来对外暴露用脱敏标识防枚举 |

**核心约定**：

- 分片键固定为 `id`（UID），**手机号不参与分片计算**（与抖音「UserID 作为单元化分区维度」一致）；
- 令牌 `sub` 携带 **UID 而非手机号**，手机号只在登录入口起作用；连表后业务按 `uid` 精准命中分片、不发生错位；
- 扩容加表只与数据量相关、与"改手机号登录"无关（见 §6）。

> 演进记录：changelog 0013（登录账号 username→phone）、changelog 0014（令牌 sub 由手机号改为 UID，完成与抖音的对齐）。
