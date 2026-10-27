# tf-redis —— Redis 拓扑装配单一事实源

> **类型**：进程内库（非独立部署单元） · **依赖方**：tf-gateway / tf-counter（tf-feed-engine 通过 Boot 自动装配）
> **代码**：`tf-redis/src/main/java/com/turbofeed/redis/`

## 1. 它解决什么问题

Redis 拓扑配置**曾经只存在于 `tf-gateway`**，而 `tf-feed-engine` 是另一个独立部署单元、
同样持有 Redis 读模型（时间线 ZSET）。后果是一次真实事故：

> changelog 0044 把单机 Redis 切成集群时只改了网关，引擎仍连已下线的 `localhost:6379`
> → 时间线读写静默失败 → **整个推荐流空白**，且日志里只有零散的连接异常。

根因不是「改漏了一处」，而是**「两个服务各配一套 Redis」这个结构本身**：
只要有两份配置，就永远有改一份漏一份的可能。

本模块把它收成单一事实源：属性命名空间 `turbofeed.redis.*` 对所有服务一致，
拓扑装配逻辑只写一次。

## 2. 拓扑三态

用**显式开关** `turbofeed.redis.mode` 选择，而不是靠「属性存不存在」推断：

| `mode` | 行为 | Bean 情况 |
|---|---|---|
| `single`（默认） | 完全交给 Boot 的 `RedisAutoConfiguration` | 本模块**一个 Bean 都不创建** |
| `cluster` | 注册 `LettuceConnectionFactory`（集群） | 顶替 Boot 的工厂 |
| `sentinel` | 注册 `LettuceConnectionFactory`（哨兵） | 顶替 Boot 的工厂 |

**为什么不用 `spring.data.redis.cluster.nodes` 的存在与否来判断**：一旦在默认配置里留一个
空占位符，就既不是单机也不是集群，行为取决于绑定后是 `null` 还是空集合——不可预测。
显式开关把「处于哪种形态」变成可读的事实。

**`single` 下零 Bean** 是刻意的：保证「克隆即跑」的行为与改造前**逐字节一致**，
不引入任何新的启动期分支。

## 3. 为什么是 `@AutoConfiguration` 而不是 `@Configuration`

本模块是**库**，位于引用方的组件扫描路径之外（引用方扫的是
`com.turbofeed.gateway..` / `com.turbofeed.counter..`，扫不到 `com.turbofeed.redis..`）。
普通 `@Configuration` 不会被扫到，引用方必须逐个 `@Import`——
那是「每加一个服务就要记得导一次」的隐性契约，**迟早会漏**（和 §1 的事故是同一类问题）。

改为 `@AutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
后，**只要引入依赖即生效**，无需任何显式导入。

配置类还带 `@AutoConfiguration(before = RedisAutoConfiguration.class)`：
**必须先于**官方自动装配注册本工厂，否则 `RedisAutoConfiguration` 先按单机建好工厂，
本类再注册会变成两个 `RedisConnectionFactory` → 注入点歧义。
先注册则官方那个带 `@ConditionalOnMissingBean` 的 Bean 自动退让。

## 4. 连接池

复用 Boot 的 `spring.data.redis.lettuce.pool.*`，**不另起一套参数**。
理由：避免「单机一套、集群另一套」的漂移——切换拓扑时池的行为必须保持不变，
否则一次拓扑切换会连带改变超时与并发特征，故障归因会被带偏。

## 5. 集群化的真实前提（运维侧，代码解决不了）

| 前提 | 说明 |
|---|---|
| `cluster-announce-ip` 必须是**真实 LAN IP** | 配成 `127.0.0.1` 会导致节点间握手失败、集群不收敛 |
| IP 漂移会复发 | 换了网络环境后集群状态变 `fail`，需要重建拓扑（步骤见 `deploy/redis/` 与部署文档） |
| 3 主 3 从、16384 槽 | 本项目验证过的拓扑；`cluster_state:ok` 且 `cluster_known_nodes=6` |

## 6. 验证

| 手段 | 覆盖 |
|---|---|
| `RedissonTopologyConfigTest`（5 例） | 三态下 Bean 的创建/退让契约 |
| 真启动 | `mode=single` 与集群两种形态下网关/引擎可正常读写时间线 |
| 事件回放 | 集群重建后 key 零丢失（历史验证记录见 changelog） |

## 7. 改动须知

- **新增服务不需要改本模块**，加依赖即可；
- **新增一种拓扑**（如 Redis Proxy / 云托管）→ 在 `RedisTopologyConfig` 加一个 `mode` 分支，
  同时补 `RedissonTopologyConfig` 的对应分支（两套客户端必须同拓扑，否则会连到不同的库）；
- **不要在本模块里写业务语义**（key 前缀归 `RedisKeys`，读写在各自服务）。
