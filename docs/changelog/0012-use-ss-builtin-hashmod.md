# Changelog 0012 · 改用 ShardingSphere 内置 HASH_MOD 算法，删除自写 HashModShardingAlgorithm

> 日期：2026-08-29 · 类型：Hotfix（编译错 + 架构精简）· 影响面：`tf-gateway` 分片全栈
> 关联：0010（重写分片为自写算法，本 hotfix 在此之上继续收敛）

## 1. 动机 / 根因

changelog 0010 引入了自写 `tf-gateway/.../sharding/HashModShardingAlgorithm`，IDE 编译报：

```
HashModShardingAlgorithm.java error: 方法不会覆盖或实现超类型的方法: line 67
```

错误定位 `@Override public Properties getProps() { ... }`。看上去像单方法问题，但**拉 jar 看实际接口字节码**发现这背后是一连串错判：

1. 自写类继承 `StandardShardingAlgorithm<Long>`，但 SS 5.5.0 的真实继承链是：
   `StandardShardingAlgorithm<T extends Comparable<?>> extends ShardingAlgorithm extends ShardingSphereAlgorithm extends TypedSPI`；
2. `TypedSPI` 接口（`org.apache.shardingsphere.infra.spi.type.typed.TypedSPI`）只声明 `default void init(java.util.Properties)`、`Object getType()`、两个 default `getTypeAliases/isDefault`，**全链路不存在 `getProps()`**——自写类的 `@Override getProps` 必然红；
3. 与此同时 `org.apache.shardingsphere.sharding.algorithm.sharding.mod.HashModShardingAlgorithm`（SS 5.5.0 内置）本来就能完全胜任，且 `HASH_MOD` 类型已登记到 SS SPI 自动加载，省去 `CLASS_BASED + algorithmClassName` 配置项；
4. 雪上加霜：内置算法用 `DataNodeInfo.prefix + paddedIndex` 拼目标名（默认 `paddingChar='0', suffixMinLength=1`），要求 `actualDataNodes` 按 `prefix${0..N-1}` 模式——这是契约，必须显式落到配置里。

**结论**：自写类在「接口正确性」、「算法一致性」、「接入简洁度」三个维度都不占优，应直接换成 SS 内置。

## 2. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `tf-gateway/src/main/java/com/turbofeed/gateway/sharding/HashModShardingAlgorithm.java` | **删除**整个文件，连带清理空 `sharding/` 包目录 |
| 2 | `tf-gateway/src/main/resources/shardingsphere-config.yaml` | 算法从 `type: CLASS_BASED + algorithmClassName=com.turbofeed...HashModShardingAlgorithm` 改为 `type: HASH_MOD + props.sharding-count: 2`（库 / 表各一条）；新增 `keyGenerators.snowflake`；`actualDataNodes` 加注释强调前缀契约 |
| 3 | `docs/architecture/sharding.md` | §4 整段重写：移除自写算法描述，改为 SS 内置选型对比表 + 工作方式拆解 |
| 4 | `docs/modules/tf-gateway.md` §1 结构树、§8 数据源分片 | 移除 `sharding/HashModShardingAlgorithm` 条目；改写算法选型段（SS 内置 + sharding-count 显式控制）；补充历史坑段落区分 0010 / 0012 |
| 5 | `docs/changelog/0010-sharding-rebuild-user-table.md` | 不再修改历史 changelog；本 0012 在关联段引用之 |
| 6 | `tf-gateway/pom.xml` | 依赖不变（`shardingsphere-jdbc:5.5.0` 是正确的，0011 hotfix 已修正） |

## 3. 影响面

- **编译**：删自写类 + 改 yaml 后，本地 `mvn -pl tf-gateway -am -DskipTests clean package` 应一次过；
- **运行时**：`HASH_MOD` 算法与 `id % sharding-count` 等价，路由结果与之前一致；
- **业务代码零改**：`user` 表的 SQL 仍然只写逻辑表名 `user`，由 ShardingSphere 重写；
- **运维契约**：`actualDataNodes` 必须保持 `ds_${0..1}.user_${0..1}` 前缀写法，改任何一侧前缀都会断路由。

## 4. 验证

| 项 | 命令 / 现象 | 预期 |
|---|---|---|
| 编译 | `mvn -pl tf-gateway -am clean package -DskipTests` | 0 报错；`HashModShardingAlgorithm` 不在 `target/classes` |
| 路由正确性 | 启动后任意写一次 `user` 表 | 日志打印 `Actual SQL: ds_x..user_x` 与 `id % 2` 规则吻合（开 `sql-show`） |
| 范围查询 | `WHERE id BETWEEN ? AND ?` | 落到两张物理表合并返回（取模破坏键序，预期行为） |
| 主键自增 | 不显式传 id 插入 | 雪花 ID 由 SS 内置 keyGenerators.snowflake 生成 |

## 5. 回滚

若 HASH_MOD 内置在生产出现路由偏差（极端情况下）：

1. 恢复 `docs/changelog/0012` 的改动：
   - `shardingsphere-config.yaml` 改回 `CLASS_BASED + algorithmClassName`，但**先修自写类的 `@Override getProps()`**：把 `public Properties getProps() { return props; }` 删掉（或加 `@Deprecated`），因为 SS 5.5.0 接口没有这方法；
   - 恢复 `tf-gateway/.../sharding/HashModShardingAlgorithm.java`（git 仓库 history）；
2. 不建议长期使用自写版，运维上易踩接口演进坑。

## 6. 教训

1. **「我猜应该是这样」是反复出问题的根**：这次没拉 jar 反编译，靠搜索结果拼接口签名，结果既漏了 `getProps()` 是否存在、又把 SS 已内置算法当成"需要自己写"；
2. **拉接口字节码才是硬证据**：SS 这种大厂的接口层级，`javap -p` 比博文 / wiki 更准；
3. **第三方依赖引入要查 GAV 是否已在中央仓库真实存在**（沿用 0011 教训），同时**先确认是否有官方内置实现可复用**，避免重复造轮子。
