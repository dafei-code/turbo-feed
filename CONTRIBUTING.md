# 贡献指南

> 面向要在这个仓库里改代码的人。**先读「提交前自检清单」那一节**，其余按需查。
> 项目定位与模块结构见 [README](README.md)；架构与设计决策见 [docs/](docs/README.md) 与 [docs/adr/](docs/adr/README.md)。

## 1. 环境准备

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **17**（`JAVA_HOME` 必须指向 17） | Boot 3.4 要求；用 21 编译出的字节码在 17 运行时起不来 |
| Maven | 3.8+ | 用 IDE 自带 Maven 时注意路径，见下方示例 |
| MySQL | 8.x（本地 3306） | 建表脚本 `deploy/mysql/init-local.sql` |
| Redis | 7.x（本地 6379；集群 7000-7005） | 单机即可跑通主流程，集群模式只影响分片拓扑 |
| MinIO | 可选（9000） | 不装则用 `turbofeed.media.storage=local` |
| Docker | 可选 | 容器化一键起 + 集成测需要 |

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 用 IntelliJ 自带 Maven 时的路径（macOS 示例）
MVN="/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn"
```

> ⚠️ **本机 `.m2` 缺依赖时不要擅自联网拉**。本项目在受限环境开发，离线优先：
> 报 `Could not resolve dependencies` 时先确认是否真的缺（而不是忘了 `-am`），
> 再决定是否联网。CI 环境不受此限。

## 2. 构建与测试

```bash
# 全量编译 + 全部单测
mvn -o clean test

# 只跑某个模块（⚠️ 必须带 -am：否则会从 ~/.m2 解析兄弟模块的旧 jar）
mvn -o -pl tf-gateway -am test

# 只跑某个测试类
mvn -o -pl tf-gateway -am test -Dtest=OutboxRelayTest -Dsurefire.failIfNoSpecifiedTests=false

# 完整 verify（含 packaging / repackage）
mvn -o clean verify

# 覆盖率（本地默认不激活，只在需要时开）
mvn -Pci,local verify

# 提交前与 CI 等价的全套检查
mvn -Pci verify
```

### 两个环境专属的坑

**① `/Volumes/D`（APFS protect 卷）上 `package` 会失败**

`spring-boot-maven-plugin` 的 `repackage` 用 `new JarFile(...)`（mmap）打开刚打出的 jar，
再 `renameTo(...jar.original)`；该卷对 mmap 文件执行 `rename()` 返回失败。
本地构建请加 `-Plocal`（跳过 repackage；本地跑用 `-cp target/classes` 不需要 fat jar）：

```bash
mvn -o -Plocal package
```

CI 在 Linux 上不激活该 profile，仍跑完整 repackage——**这正是 CI 存在的意义之一**。

**② 项目根目录有一个 gitignored 的 `application.yml`**

Spring Boot 加载 `file:./application.yml` 的优先级**高于** classpath 里的同名文件。
因此：

- `-Dspring.profiles.active=local` 与 `application-local.yml` 可能**不生效**；
- 新增分片表必须同步补 `tf-gateway/src/main/resources/shardingsphere-config-local.yml`
  （它同样 gitignored），否则运行期报 `Table or view 'xxx' does not exist`；
- 排查「我改了配置但没生效」时，**第一步先确认根目录这个文件有没有覆盖你**。

## 3. 本地真启动

单测通过 ≠ 能启动。涉及 Spring 装配（`@ConditionalOnProperty`、Bean 互斥、拦截器链）的改动
**必须真启动一次**——这类问题单测和编译都不会告诉你。

```bash
# 必需环境变量（部分刻意无默认值，缺失即启动失败）
export TURBOFEED_JWT_SECRET="至少32位的随机串"
export TURBOFEED_INTERNAL_TOKEN="网关与引擎必须一致"
export TURBOFEED_SNOWFLAKE_WORKER_ID=1
export TURBOFEED_SNOWFLAKE_DATACENTER_ID=1

java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar      # :8080
java -jar tf-feed-engine/target/tf-feed-engine-0.1.0-SNAPSHOT.jar  # :8083
java -jar tf-counter/target/tf-counter-0.1.0-SNAPSHOT.jar      # :8081
```

**用 classpath 方式跑（改代码后免打包）**：

```bash
mvn -o -q -pl tf-feed-engine -am dependency:build-classpath \
    -Dmdep.outputFile=/tmp/engine-cp.txt -Dmaven.resources.skip=true

# ⚠️ build-classpath 不会输出 reactor 兄弟模块，必须手工补上它们的 target/classes
java -cp "tf-shared/target/classes:tf-redis/target/classes:tf-hotspot/target/classes:\
tf-feed-engine/src/main/resources:tf-feed-engine/target/classes:$(tr '\n' ':' < /tmp/engine-cp.txt)" \
    com.turbofeed.feedengine.FeedEngineApplication \
    --server.port=18082 --spring.profiles.active=local
```

> `--spring.profiles.active=local` 是必需的：引擎在**非 `dev`/`local` profile 且未配
> `TURBOFEED_INTERNAL_TOKEN`** 时会**启动失败**（内部接口不允许裸奔），这是刻意设计。
>
> 另：本机引擎监听在 IPv6 `*:8083`，用 `127.0.0.1` 可能连不上，用 `localhost`。

### 临时探针的纪律

为了验证某个行为而写的临时日志 / 临时类 / 临时 SQL，**验证完立即删除**，
并在提交信息里说明验证方式。仓库里不允许留下调试残留——
它们会变成没人敢删、也没人知道为什么在那里的「僵尸代码」。

`@Disabled` 同样不允许裸用（CI 会检查）：跳过一条测试等于悄悄关掉一条门禁。
需要环境依赖时用项目自定义的 `@RequiresDocker` 等守卫，它会**显式**说明跳过原因。

## 4. 提交规范

### 提交信息

**Conventional Commits + 中文正文**。标题 50 字内，正文说清三件事：

```
fix(counter): 计数落库事务闭合

<为什么会有这个问题：失效形状是什么、影响谁、为什么不报错>

<怎么改：关键取舍与反直觉之处；否决了什么方案、为什么>

<怎么验证：跑了什么、结果如何；哪些没验证、为什么>
```

常用 type：`feat` / `fix` / `refactor` / `perf` / `test` / `docs` / `ci` / `chore`。

**正文里最重要的是「为什么」**。代码已经说明了「做了什么」，
6 个月后需要复盘的是「当时为什么这么做、还有哪些路没走」。

### 分支

- `main` 保持可发布；功能与修复走短分支，合并前跑 `mvn -Pci verify`；
- 一个大改拆成若干个**可独立回滚**的提交（按主题，不按文件）；
- 提交与推送前确认 `git status` 干净——本项目多次遇到编辑工具（`sed -i ''` / `perl -i`）
  在 protect 卷上留下 `*.orig` / 随机名残留文件被误提交，CI 有对应检查。

## 5. 提交前自检清单

按顺序过一遍，**每一步都能挡住一类真实事故**：

- [ ] `mvn -o -pl <module> -am test` 绿（带 `-am`，别用 `.m2` 里的旧兄弟 jar）
- [ ] 全仓 `mvn -o test` 绿（改公共模块时必跑）
- [ ] 涉及 Spring 装配 → **真启动一次**，确认不是「编译过、启动炸」
- [ ] 新增门禁（ArchUnit / CI 检查）→ 做过**非空洞性反证**：临时制造一处违规 → 确认变红 → 删除 → 转绿
- [ ] 临时探针 / 调试代码已删除，`git status` 干净
- [ ] 契约变更 → 同步 `docs/architecture/contract-evolution.md` 与 golden 测试
- [ ] 配置键变更 → 旧键保留为 `@Deprecated` 委托访问器，并在模块文档标注
- [ ] 新增异步链路 → 更新 `docs/architecture/failure-mode-ledger.md` §2
- [ ] 架构决策（依赖 / 契约 / 故障口径 / 否决方案）→ 新增 ADR 并更新 [索引](docs/adr/README.md)
- [ ] 代码变更 → 同一提交内更新对应 `docs/modules/*.md`，按编号追加 `docs/changelog/`

## 6. 代码约定（本项目的特殊之处）

| 约定 | 为什么 |
|---|---|
| **契约类不用 `is` / `get` 前缀**（如 `Result#succeeded()`） | Jackson 会把 `isSuccess()` 当属性序列化出一个幽灵字段；契约类又不能加 `@JsonIgnore`（tf-shared 零依赖） |
| **tf-shared 只准用 JDK** | 它被每个服务内嵌，框架耦合会顺着依赖图扩散。有 ArchUnit 规则拦着 |
| **`@ConditionalOnProperty` 的属性名必须与方法/字段名逐字一致** | 它按字面量查 Environment，**不做** relaxed binding；写错不报错、开关不动 |
| **同类内部调用不要指望 `@Transactional` / `@Async` 生效** | 不走代理。需要事务就用 `TransactionTemplate` 显式写 |
| **`@Component` + 方法级 `@Bean` 会重复注册** | 需要「抢占发生在 Bean 方法里」这类逻辑时，类上别再加 `@Component` |
| **fail-open 处必须留可观测痕迹** | 「静默降级」和「功能正常」在外部看起来一样，没有指标/日志就无法区分 |
| **空集合 / 空结果要区分「没有」与「失败」** | 用 `Optional.empty()` vs `Optional.of(List.of())`，别用同一个 `null` 表达两种含义 |

## 7. 常见故障速查

| 现象 | 先查 |
|---|---|
| 启动报 `Table or view 'xxx' does not exist` | 本地 `shardingsphere-config-local.yml` 是否缺该表注册（SS 启动期加载元数据，改完要重启） |
| 配置改了不生效 | 项目根目录 gitignored 的 `application.yml` 覆盖了 classpath 配置 |
| `No instances available for 127.0.0.1` | 用了 `@LoadBalanced` builder 做直连。直连必须用 `directRestClientBuilder` |
| 单测报 `UnnecessaryStubbingException` | Mockito 严格模式：提前 return 的分支、未被调用的 stub 要删 |
| `mvn package` 报 `Unable to rename ... .jar.original` | D 盘 protect 卷限制，加 `-Plocal` |
| 登录失败但 HTTP 200 | 鉴权失败也返回 200，必须读 body 里的 `code`（40101 未登录 / 40301 无权限） |
| Redis `cluster_state:fail` / IP 漂移 | 集群 `cluster-announce-ip` 必须是真实 LAN IP，重启前 `export REDIS_CLUSTER_ANNOUNCE_IP=$(ipconfig getifaddr en0)` |

更多症状 → 证据的映射见 [失败模式对账 §6](docs/architecture/failure-mode-ledger.md)，
**第一步永远是拿 traceId**（响应头 `X-Trace-Id`，然后一条 `grep` 拉出整条链）。
