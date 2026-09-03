# 0015 - 升级 ShardingSphere JDBC 至 5.5.3（修复 5.5.0 test-util 缺失发布缺陷）

## 动机

`tf-gateway` IDEA Build 报错（用户截图，2026-08-29）：

```
Could not find artifact org.apache.shardingsphere:shardingsphere-test-util:pom:5.5.0 in central
```

复核根因（按"第三方依赖必须先查 Maven Central GAV"硬证据规则）：

1. `shardingsphere-jdbc-5.5.0.pom` 第 38-41 个 `<dependency>` 官方声明：
   ```xml
   <dependency>
     <groupId>org.apache.shardingsphere</groupId>
     <artifactId>shardingsphere-test-util</artifactId>
     <version>${project.version}</version>
     <scope>test</scope>
   </dependency>
   ```
   但 `https://repo.maven.apache.org/maven2/org/apache/shardingsphere/shardingsphere-test-util/` 返回 404——
   **该工件未发布到 Maven Central**，导致整条传递依赖链解析失败。
2. Apache 官方 GitHub Issue [#31128](https://github.com/apache/shardingsphere/issues/31128)、
   [#32256](https://github.com/apache/shardingsphere/issues/32256) 用户从 2024-07 持续上报。
3. Apache PR [#31143](https://github.com/apache/shardingsphere/pull/31143)（2024-05-06 merged，标题
   "Removes test modules that are not deployed to Maven Central Repository"）已修复，
   5.5.1 起 POM 干净，5.5.3 是当前 5.5.x 线最新稳定版（2026-02-23 发布）。
4. 我们 tf-gateway/pom.xml **并未**自行声明 `shardingsphere-test-util`，是传递依赖被拉进来——
   排除也不需要在我们的 pom 里改，是 SS 官方自己在 release 时漏发的工件。

为什么 `<scope>test</scope>` 也报红：Maven 解析传递依赖时会下所有声明的 artifact
（即便 test scope），必须验证 POM 是否真实存在，否则整条依赖链失败。
这是 SS 5.5.0 + Spring Boot 3.x 的经典踩坑。

## 改动清单

| # | 文件 | 改动 | 类型 |
|---|---|---|---|
| 1 | `tf-gateway/pom.xml` | `shardingsphere-jdbc` 版本 `5.5.0` → `5.5.3`；pom 注释同步（补一句"5.5.3 相比 5.5.0 已修复 test-util 缺失发布"并指明 PR/issue 证据）| 升级 |
| 2 | `tf-gateway/src/main/resources/shardingsphere-config.yaml` | 文件头注释版本号 `5.5.0` → `5.5.3`，并加一行说明 test-util 缺陷与 PR 引用 | 同步 |
| 3 | `docs/architecture/sharding.md` | 文档头部载体声明（§0/§2/§4 表格/§6 末尾 pom 引用）四处版本号 `5.5.0` → `5.5.3`；§6 pom 引用补一句"5.5.3 是当前最新稳定版，相比 5.5.0 已修复 test-util 缺陷（PR #31143），无需 exclusion" | 同步 |
| 4 | `docs/modules/tf-gateway.md` §8 | 三处版本号 `5.5.0` → `5.5.3`；§8 末尾补一段 "5.5.3 vs 5.5.0" 说明 | 同步 |

> ⚠️ **不改**：`docs/changelog/0010-sharding-rebuild-user-table.md`、
> `0011-fix-shardingsphere-dep-id.md`、`0012-use-ss-builtin-hashmod.md`——这些是历史 changelog
> 记录"当时我们用了 5.5.0"的事实，升级属于另一回事，不能改写历史。
>
> ⚠️ **不动**：`tf-gateway/target/classes/shardingsphere-config.yaml`——maven build 产物，
> 下次构建自动重生成。

## 选型决策

| 候选方案 | 改动量 | 决策 | 理由 |
|---|---|---|---|
| **A. 升 5.5.3**（采用）| 1 行 version | ✅ | 官方已修、零 exclusion 历史包袱、与抖音同代际最新稳定版 |
| B. 留 5.5.0 + 加 exclusion | 6 行 `<exclusions>` | ❌ | 每次升级都得记得删 exclusion；背历史伤痕 |
| C. 升 5.5.1 / 5.5.2 | 1 行 version | ❌ | 5.5.3 是 5.5.x 线最新，没必要停在中段 |

## 影响面

- **构建**：IDEA 红色波浪消失；`mvn -pl tf-gateway -am clean package` 应 BUILD SUCCESS
- **运行时**：SS JDBC 5.5.0 → 5.5.3 是 patch 版本，按 Semver 承诺零返工；
  分片算法 HASH_MOD 配置契约（`type: HASH_MOD` + `props.sharding-count`）不变，
  `actualDataNodes: ds_${0..1}.user_${0..1}` 模板不变
- **依赖体积**：5.5.3 POM 比 5.5.0 少约 5 个测试模块依赖（test-util、test-fixture-database 等），
  最终 jar 体积略减
- **API 兼容**：用户侧无感（接口链 `TypedSPI → ShardingSphereAlgorithm → ShardingAlgorithm →
  StandardShardingAlgorithm` 在 5.5.0→5.5.3 之间未变）

## 验证

```
mvn -pl tf-gateway -am clean package -DskipTests
mvn -pl tf-gateway -am dependency:tree | grep -E "shardingsphere-(jdbc|test)"
```

预期：
- BUILD SUCCESS
- 输出包含 `shardingsphere-jdbc:jar:5.5.3`
- **不再**出现 `shardingsphere-test-util`（5.5.3 POM 已剔除）

启动验证：
```
mvn -pl tf-gateway -am spring-boot:run
```
预期：DataSource 初始化无 `Cannot find datasource` / `ScannerException`；
首次 `POST /api/auth/login` 走通，user 表分片正常（demo 无 DB 时仍可正常启动到 404/健康检查）。

## 回滚

```bash
git diff HEAD~1 tf-gateway/pom.xml   # 确认改动面
git checkout HEAD~1 -- tf-gateway/pom.xml tf-gateway/src/main/resources/shardingsphere-config.yaml docs/
```

或按 B 方案加 exclusion：
```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc</artifactId>
    <version>5.5.0</version>
    <exclusions>
        <exclusion>
            <groupId>org.apache.shardingsphere</groupId>
            <artifactId>shardingsphere-test-util</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

## 复盘

- **踩坑模式**：第三方依赖 release 缺陷（官方 POM 声明了未发布的工件），单看错误日志会以为是 pom 坐标错；
  按"先查 Maven Central GAV + 看完整 POM dependencies 段"的方法论能快速区分**自错** vs **官方错**。
- **方法论沉淀**：第三方依赖出问题时，标准动作——① 读官方 POM 完整 dependencies 段；② 查 Maven Central
  列表确认工件是否存在；③ 查 GitHub issue 区分已知 release 缺陷 vs 我方误用；④ 决定升级 / 加 exclusion / 降版本。
- **后续**：SS 5.5.0 这一坑已被社区踩了一年半，5.5.1 起干净，无需任何 workaround。