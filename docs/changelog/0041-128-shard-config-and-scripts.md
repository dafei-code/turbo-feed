# 0041 128 分片扩容：配置其实起不来，建表脚本也不匹配

## 问题：一套「已就绪未切换」的方案，两处致命错误

此前文档把 128 分片描述为「配置与建表脚本已就绪，未切换」。实际核查发现它**连启动都做不到**：

| # | 问题 | 后果 |
|---|---|---|
| 1 | `shardingsphere-config-128.yaml` 用手动 `tables` + `HASH_MOD` | 启动即失败：`AlgorithmInitializationException: 'user' tables sharding configuration can not use auto sharding algorithm` |
| 2 | 两份 yaml 的 JDBC URL 缺 `allowPublicKeyRetrieval` | `LOAD FAILED: Public Key Retrieval is not allowed` |
| 3 | `expand_media_tables_128.sql` 按「每库各 `media_0..63`」建表 | 与 autoTables 推导的物理表名不符，切过去必 `TableNotFoundException` |
| 4 | 该脚本缺 `caption`/`caption_mark`/`post_id`/`seq` 四列与 `idx_user_post` 索引 | 照它建表，写文案/分组直接 `ColumnNotFoundException` |
| 5 | `!SINGLE` 未登记 `user_phone_router` / `outbox_event` | 切换后这两张表查不到，登录定位与时间线投递整体失效（同 0036/0037 的坑重演） |
| 6 | `media_schema.sql`（4 分片版）同样用了每库编号 + 缺 4 列 | 与线上真实物理表名（`media_0/media_2` vs `media_1/media_3`）对不上 |

## 根因：autoTables 的物理表名是「全局连续编号」

`HASH_MOD` 在 5.5.x 属**自动分片算法**，只能挂在 `autoTables`。而 autoTables 由
`sharding-count` 推导物理表：**第 i 张表落到 `actualDataSources.get(i % 库数)`**，
即 `ds_0` 拿偶数下标、`ds_1` 拿奇数下标，编号是全局 0..N-1，**不存在「每库重新从 0 开始」**。

所以 128 分片下：`turbo_feed_1` 持有 `media_0/_2/_4/.../_126`，
`turbo_feed_2` 持有 `media_1/_3/.../_127`。

## 改动

| 文件 | 改动 |
|---|---|
| `shardingsphere-config-128.yaml` | 重写为 `autoTables`：`hashMod4`（user/account_credit/violation_record/account_penalty/report/appeal/comment）、`hashMod128`（media）；`!SINGLE` 补齐 4 张单表；JDBC URL 补 `allowPublicKeyRetrieval`；连接池 50/10 |
| `db/expand_media_tables_128.sql` | 重生成：全局连续编号 0..127，表结构同步 `init-local.sql` 权威定义 |
| `db/rebalance_media_128.sql`（新增） | 存量搬迁 DML，与建表 DDL **分文件**（避免误把搬迁当建表跑） |
| `db/media_schema.sql` | 同步修正：4 张物理表改为 `turbo_feed_1.media_0/_2` + `turbo_feed_2.media_1/_3`，补齐 4 列 1 索引 |

## 关键实证一：扩容 4 → 128「库不变」，只换库内表号

`idx128 % 4 == idx4` 恒成立，且 `idx128 % 2 == idx4 % 2`，因此**源库 == 目标库**，
存量搬迁是**库内搬迁，不跨库、不跨机**：

```
user_id=1000000000000000000   4片: ds_1.media_1  ->  128片: ds_1.media_77   [库不变]
user_id=1000000000000000001   4片: ds_0.media_2  ->  128片: ds_0.media_78   [库不变]
user_id=1000000000000000002   4片: ds_1.media_3  ->  128片: ds_1.media_79   [库不变]
```

这一条决定了迁移成本：不需要跨库导数据，源 `media_X` 只是拆到 `Y ∈ {X, X+4, ..., X+124}`
共 32 张同库目标表。

## 关键实证二：HASH_MOD 可以用纯 SQL 表达，故搬迁不必依赖程序

Java 侧是 `Math.abs(user_id.hashCode()) % N`（`Long.hashCode() = (int)(v ^ (v>>>32))`）。
等价的 MySQL 表达式：

```sql
ABS(IF(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) >= 2147483648,
        CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED) - 4294967296,
        CAST(((`user_id` ^ FLOOR(`user_id`/4294967296)) & 4294967295) AS SIGNED))) % 128
```

⚠️ 减法前必须 `CAST(... AS SIGNED)`——XOR 结果是无符号 BIGINT，直接减会
`Data truncation: BIGINT UNSIGNED value is out of range`（实测踩到）。

已用 29 个 id × {2, 4, 128} 与 Java 逐值交叉比对，**全部一致**。

## 迁移脚本为什么必须是「复制 + 清理」两阶段

`media_0..media_3` **本身就是 128 张目标表的成员**（下标 0..3），所以：

- 只复制不删 → 同一条 `media_id` 存在两份，切换后重复计数（干跑实测 123 → 246）；
- 只删不复制 → 直接丢数据。

保留条件是 `expr % 128 == 自己的下标`（即 `idx ∈ {0,1,2,3}` 的行原地不动）。

## 验证

| 项 | 结果 |
|---|---|
| `SsProbe` 加载 128 配置 | `metadata loaded OK`，9 张逻辑表路由全部解析成功 |
| 建表脚本实机执行 | 128 张建成：`turbo_feed_1` 64 张（0..126 全偶）、`turbo_feed_2` 64 张（1..127 全奇） |
| 迁移脚本干跑（事务内执行后回滚） | 总行数 123 → 123 **守恒**；逐表错位检查 **misplaced = 0**；回滚后 **零副作用** |

## 切换步骤（仍未切换，仅把前置补齐到可执行）

1. 执行 `db/expand_media_tables_128.sql`；
2. 执行 `db/rebalance_media_128.sql`（生产建议双写 + 灰度，勿停机硬切）；
3. `application.yml` 的 `spring.datasource.url` 指向 `classpath:shardingsphere-config-128.yaml`；
4. **重启应用**——ShardingSphere 在启动时加载元数据，建表/加列后不重启不生效（0033/0034 已踩两次）。
