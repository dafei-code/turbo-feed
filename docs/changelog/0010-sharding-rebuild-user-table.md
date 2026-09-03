# 0010 - 分库分表整体重构（去除优惠券项目残留）+ user 表建表语句

- 日期：2026-08-29
- 类型：重构（feat(sharding)）+ 文档

## 动机

`tf-gateway/sharding/` 与 `shardingsphere-config.yaml` 是从优惠券开源项目（`com.nageoffer.onecoupon`）整段复制而来，与 turbo-feed 完全不匹配，且处于**编译不过 + 运行必崩**状态：

1. `pom.xml` 缺失 ShardingSphere 依赖，三个算法类 `import org.apache.shardingsphere.*` 编译失败；
2. `application.yml` 的 `url` 与 `driver-class-name` 写反（url 写成驱动类名、driver 写成 jdbc url）；
3. `shardingsphere-config.yaml` 引用 `com.nageoffer.onecoupon...DBHashModShardingAlgorithm`、表是 `t_coupon_template`/`t_user_coupon`、`shop_number` 分片键——整套是优惠券域；
4. `DBShardingUtil` 经 Hutool `Singleton.get("coupon-template", ...)` 取实例，拿到的是未 `init` 的实例（`shardingCount=0`）→ `getShardingMod` 除零崩溃；还硬编码 `ds0/ds1`、`doCouponSharding` 等优惠券命名；
5. 两算法用 `shardingValue.hashCode()` 取模——`Long.hashCode()` 截断高 32 位致大 ID 倾斜；
6. `RangeShardingValue` 返回空集 → `BETWEEN/>/<` 查询静默空结果；
7. 两算法 95% 重复，且 `DBHashMod` 的 `sharding-count` 跨库公式与 `actualDataNodes` 数量不自洽。

用户明确要"复核逻辑 + 优化分库分表代码和项目结构"，故整体替换为 turbo-feed 专属实现。

## 改动清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `sharding/HashModShardingAlgorithm.java` | 新增 | 唯一分片算法：`hash(id) % availableTargetNames.size()` 取模；库/表策略复用；范围查询广播全部分片；含完整设计注释 |
| `sharding/DBHashModShardingAlgorithm.java` | 删除 | 优惠券残留（除零 + hashCode 截断） |
| `sharding/TableHashModShardingAlgorithm.java` | 删除 | 与上式 95% 重复 |
| `sharding/DBShardingUtil.java` | 删除 | Hutool Singleton 取实例导致除零，硬编 ds0/ds1 |
| `resources/shardingsphere-config.yaml` | 重写 | turbo-feed `user` 表：2 库 × 2 表，分片键 `id`，两个 `CLASS_BASED` 算法指向 `HashModShardingAlgorithm`；删除全部 coupon 残留 |
| `resources/application.yml` | 修正 | datasource `driver-class-name` / `url` 复位（ShardingSphere 原生驱动）；移除顶层 username/password（已迁入 config yaml） |
| `pom.xml` | 新增 | `shardingsphere-jdbc:5.5.0` 依赖（⚠️ 旧版误写为 `shardingsphere-jdbc-core`，后者不在 Maven Central；正式名见 SS 5.x 官方坐标） |
| `resources/db/user_schema.sql` | 新增 | `user` 表 4 物理表建表 DDL + 跨分片唯一性坑说明 |
| `controller/MediaController.java` | 修正 | 限流说明同步为「Sentinel 机器维度 + Redis 用户维度」，去除过时的"Sentinel 热点参数按 userId / 全局 500 QPS" |
| `docs/modules/tf-gateway.md` | 同步 | §结构树加 `sharding/` 包 + 资源注记；新增 §8 分库分表； |
| `docs/architecture/sharding.md` | 新增 | 分片设计文档（分片键选择 / 方案 / 算法 / 跨分片唯一性 / 扩容路径） |
| `docs/changelog/0010-*.md` | 新增 | 本记录 |

## 影响面

- 仅网关元数据层接入 ShardingSphere；当前 `user` 表尚无 Entity/Mapper，业务代码未强依赖，接入数据源后无其他模块受影响；
- 分片算法收敛为 1 个（`HashModShardingAlgorithm`），后续其他表（如媒体元数据）可直接复用，无需再写算法类；
- 若本地无 MySQL（config 指向 127.0.0.1:3306/turbo_feed_1|2），应用启动会因连不上数据源失败——需先建库或改 config 指向可用 MySQL。

## 验证

- 编译：本机 `mvn -pl tf-gateway -am compile`（沙箱无 Maven）；确认 `shardingsphere` 包导入全部解析、无 `com.nageoffer` 残留；
- 路由：本地起 MySQL 建 `turbo_feed_1`/`turbo_feed_2` 并执行 `user_schema.sql` 后启动，`sql-show: true` 观察 `INSERT/SELECT` 实际落到的物理库/表是否符合 `hash(id)%2`；
- 范围：对 `id` 做 `BETWEEN` 查询应广播 4 张表并合并，不出现空结果。

## 回滚

- 恢复本次改动（`git checkout` 相关文件）；若暂不需要分片，将 `application.yml` 的 datasource 切回直连 MySQL/H2 即可，算法类与 config yaml 可保留作后续接入。
