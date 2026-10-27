# ADR-002：接入 Nacos 服务发现 / 配置中心（P0-5）

- **状态**：已采纳（Accepted，2026-10-17）
- **所属改进项**：`improvement-plan.md` §3 / P0-5
- **决策人**：dafei
- **关联 ADR**：ADR-001（测试，扩实例前先有回归保护）、ADR-003（tf-counter，多实例计数落地依赖服务发现）

## 1. 背景（Context）

当前网关直连 `turbofeed.feed.engine.base-url=http://localhost:8083`（`FeedEngineProperties`），多实例扩不动：

- 引擎扩容到 2 实例，网关只能配一个地址，另一个不接流量；配负载均衡则无健康检查 / 实例摘除；
- Sentinel 限流规则只在本地 JSON 文件，改规则要重启；
- 多实例雪花 worker-id 靠环境变量手填，撞号风险高。

**利好**：项目已引 `spring-cloud-alibaba-dependencies` BOM（2023.0.3.4）+ `spring-cloud-starter-alibaba-sentinel`，Nacos 与 Sentinel 同源、零额外版本成本；`FeedEngineClient` / `FeedEngineProperties` 已预留切换注释（"引入 Nacos + Spring Cloud LoadBalancer 后 base-url 退化为兜底"）。

## 2. 决策（Decision）

1. **服务发现**：引入 `spring-cloud-starter-alibaba-nacos-discovery`（复用已引 BOM，零版本成本）。`FeedEngineClient` 改为 `@LoadBalanced` `RestClient` + 服务名寻址（切换点已预留，代码基本不动）。
2. **Sentinel 规则动态推送**：Sentinel 规则数据源切 Nacos，规则改动能热推，不再改本地文件重启。
3. **worker-id 分配**：由 Nacos 实例注册表 / 配置中心统一分配（或降级为数据库号段模式），消除手填撞号。
4. **配置中心（本期范围）**：先落"服务发现 + Sentinel 规则数据源"；Nacos 作为配置中心（统一管理 `base-url` 等）作为后续可选，不在本期强制——避免一次引入过多变动。
5. **本地兼容**：未配 Nacos 时保持"克隆即跑"——`base-url` 兜底逻辑保留，缺失 Nacos 地址不 fail-fast（与现有 `local-dev` 体验一致）。

## 3. 后果（Consequences）

**正面**
- 引擎多实例自动注册 / 健康检查 / 负载均衡 / 故障摘除，真正扩得动；
- 限流规则热推，运维无需重启；
- worker-id 集中分配，消除撞号。

**负面 / 成本**
- 新增 Nacos Server 依赖（本地 `docker-compose` 补一个 nacos 服务；生产独立部署）；
- 服务间调用从"直连固定 host"变"服务名 + LB"，需补集成测（见 ADR-001 的 `FeedEngineClient` 测试）验证寻址正确；
- 排障路径多一跳（Nacos 注册状态）。

## 4. 备选方案（Alternatives Considered）

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| Eureka | **否决** | 与已用的 Spring Cloud Alibaba 栈不一致，Sentinel 规则推送要另接 |
| Consul | **否决** | 同上的栈不一致；引入额外 agent |
| 只引配置中心、不引服务发现 | **否决** | 解决不了"多实例寻址 + 摘除"这个核心痛点 |
| 纯 Kubernetes Service（不加 Nacos） | **暂缓** | K8s Service 能解决寻址，但 Sentinel 规则热推仍需 Nacos；且当前部署含非 K8s 场景 |

## 5. 落地顺序（与 ADR-001 / ADR-003 协同）

1. **先 ADR-001（测试）**：多实例 / 寻址改动有回归保护后再动；
2. **再 ADR-002（Nacos）**：服务发现 + Sentinel 规则热推；
3. **后 ADR-003（tf-counter）**：计数服务多实例落地依赖服务发现寻址。

## 6. 落地记录（2026-10-19）

P0-5 第一批已落地「**服务发现（双向 opt-in）+ Sentinel 规则 Nacos 数据源（opt-in）**」两部分；worker-id 集中分配经评审决定**回退至 phase-2**（见 §6.3）。

### 6.1 服务发现（双向 opt-in，已落）

- **依赖**：`tf-gateway` / `tf-feed-engine` 各加 `spring-cloud-starter-alibaba-nacos-discovery` + `spring-cloud-starter-loadbalancer`（版本由根 pom 已 import 的 `spring-cloud-alibaba-dependencies(2023.0.3.4)` / `spring-cloud-dependencies(2024.0.2)` BOM 托管，未写死 version）。
- **接入点**：两模块各新增 `config/ServiceDiscoveryRestClientConfig`，提供一个 `@LoadBalanced RestClient.Builder`（与 Boot 自动装配的 plain builder 共存，按需选用）。
- **客户端切换**：
  - 网关 `FeedEngineClient`：`FeedEngineProperties.Engine` 加 `serviceName`（默认空）。空 → plain builder 直连 `base-url`；非空 → `@LoadBalanced` builder + `baseUrl("http://" + serviceName)` 由 Nacos 解析。
  - 引擎 `GatewayReviewClient`：加 `turbofeed.gateway.service-name`（默认空），逻辑同上（引擎→网关晋级回调寻址）。
- **严格 opt-in**：`spring.cloud.nacos.discovery.enabled` 默认 `false`（env `TURBOFEED_NACOS_ENABLED`），`server-addr` 缺省不连；两处 `service-name` 默认空 → 行为与改造前**逐字节一致**，克隆即跑零回归。

### 6.2 Sentinel 规则 → Nacos 数据源（opt-in，已落）

- **依赖**：`tf-gateway` 加 `sentinel-datasource-nacos`（BOM 托管）。
- **新增 `SentinelNacosDataSourceConfig`**：`@ConditionalOnProperty(turbofeed.sentinel.nacos.enabled=true)` 时建 `NacosDataSource<List<FlowRule>>` 并 `FlowRuleManager.register2Property` 绑定；解析用应用级 `ObjectMapper`（Jackson）避 fastjson，规则体为 Sentinel FlowRule 标准字段。
- **本地规则让位**：`SentinelRateLimitConfig` 加 `@ConditionalOnProperty(turbofeed.sentinel.nacos.enabled=false, matchIfMissing=true)`——开启 Nacos 时本类不加载，避免两套规则互相覆盖。关闭（默认）时仍是本地代码注册，克隆即跑。
- **配置项**：`turbofeed.sentinel.nacos.{enabled,server-addr,group,data-id}`，默认全关。

### 6.3 worker-id 集中分配（phase-2，本期不做）

- **决策**：原决策 §2.3「由 Nacos 统一分配 worker-id」本期**不做**，回退为 phase-2。理由：集中分配需引入「Nacos 注册表读实例序号 / 数据库号段」等本沙箱无法验证的链路，违反「未验证的复杂性不引入」纪律；当前 `SnowflakeConfig` 的 env-var 硬失败契约（`requireInstanceId`，缺失即启动失败）已能保住「多实例不撞号」这条底线，短期够用。
- **phase-2 触发条件**：当真正需要「免手填 worker-id 的多实例一键扩缩容」时再评估（届时与 tf-counter / K8s StatefulSet 序号派生方案一并设计）。ADR-003（tf-counter）多实例落地的寻址已在本期服务发现中具备。

### 6.4 验证状态

- **已验证（本地）**：两模块 `mvn compile` 通过；默认配置（Nacos 全关）真启动行为与改造前一致（直连 + 本地 Sentinel 规则），零回归。
- **待验证（需 Nacos Server / CI）**：服务发现真路径（多实例注册 + LB 寻址 + 故障摘除）、Sentinel 规则 Nacos 热推。沙箱无 Nacos 环境，同靶心④（Testcontainers 真 MySQL）处理方式——代码就位、开关默认关、真链路留待有 Nacos 的环境确认。
