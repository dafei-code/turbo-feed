# 0011 — ShardingSphere 依赖坐标修正 + tf-shared 去重

- **状态**：hotfix
- **影响模块**：`tf-gateway`
- **日期**：2026-08-29

## 动机

changelog 0010 落地后用户反馈「IDEA 里 `@Override` 标红 + `no usages` 全军覆没」。查实：
`pom.xml` 写了 `org.apache.shardingsphere:shardingsphere-jdbc-core:5.5.0`，**这个
artifactId 在 Maven Central 不存在**——ShardingSphere 5.x 官方正确坐标是
`shardingsphere-jdbc`（或带 spring-boot-starter 后缀：`shardingsphere-jdbc-core-spring-boot-starter`）。
非法坐标导致 `import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm`
解析失败，进而 `@Override`、`no usages` 全部连锁报红。

> 工程教训：上一轮我交付时 grep 了「是否有 coupon 残留」「路径是否对」就声称「配置算法名与
> 新类一致、编译依赖已补」——但根本没核 `artifactId` 拼写。这是连续第三次凭"已核对"字面
> 交差、实际漏掉真问题。本 changelog 强制约束：「引入第三方依赖 ⇒ 必须先搜一次 Maven
> Central 确认 GAV 三元组」。

附带还查出 `tf-shared` 在 pom 里声明了两次（不带 `<version>` + 显式带 `<version>`），是
duplicate 警告源头。

## 改动清单

| 文件 | 动作 | 要点 |
|---|---|---|
| `tf-gateway/pom.xml` | 修正 | `<artifactId>shardingsphere-jdbc-core</artifactId>` → `shardingsphere-jdbc`；删除第二次 `tf-shared` 重复声明 |
| `tf-gateway/pom.xml` | 注释补充 | 在 sharding 依赖上明示「⚠️ artifactId 必须是 `shardingsphere-jdbc`，不是 `shardingsphere-jdbc-core`」 |
| `tf-gateway/.../sharding/HashModShardingAlgorithm.java` | 加日志 | `@Slf4j` + 路由失败 throw 之前 `log.error(...)` 带 `logicTable/column/value/targets` 上下文，便于排障 |
| `docs/changelog/0010-sharding-rebuild-user-table.md` | 文字修正 | "依赖" 行同步为正确 artifactId |
| `docs/architecture/sharding.md` §版本 / 坐标 | 文字修正 | "pom.xml" 行同步为正确 artifactId + 错误 artifactId 警示 |
| `docs/modules/tf-gateway.md` §数据源分片 | 文字修正 | "版本" 行同步为正确 artifactId + 警示 |
| `docs/changelog/0011-fix-shardingsphere-dep-id.md` | 新增 | 本记录 |

## 影响面

- 仅 tf-gateway；编译/启动行为应直接通过（不需要任何代码改动之外的额外配置）；
- 算法行为不变；日志仅在路由失败这种本不该发生的分支打栈，可观测性增强。

## 验证

1. 本机：
   ```
   mvn -pl tf-gateway -am -DskipTests clean package
   ```
   预期：BUILD SUCCESS；`mvn dependency:tree | grep shardingsphere` 应输出 `shardingsphere-jdbc:jar:5.5.0`；
2. 启动：接入 MySQL 后 `sql-show: true` 应正常打印物理库/表路由；
3. 算法层冒烟：单 ID 路由 / 范围查询（见 changelog 0010 验证章节）。

## 回滚

```
git revert --no-edit <本次提交>
```
