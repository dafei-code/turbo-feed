# tf-gateway 技术文档

> HTTP 接入层：唯一对外暴露的业务端口。当前承载登录鉴权与 UGC 图片上传（审核后展示）完整闭环。
> 父包 `com.turbofeed.gateway`，独立 Spring Boot 服务，默认端口 8080。

## 1. 代码结构

```
com.turbofeed.gateway
├── GatewayApplication          # 启动类（@SpringBootApplication，仅扫 gateway 包）
├── controller/                 # 协议适配层（薄）：参数绑定 + 统一返回结构
│   ├── AuthController          #   POST /api/auth/login
│   └── MediaController         #   POST /api/media/upload
├── service/                    # 业务层
│   ├── AuthService             #   登录校验（演示账号）+ JWT 签发
│   ├── MediaUploadService      #   上传主链路（校验外移责任链，见 §3）
│   ├── ImageFormat             #   Magic Number 格式判定（白名单）
│   ├── event/                  #   事件总线（Observer）
│   │   ├── MediaEventPublisher         # 端口：发布抽象
│   │   ├── LocalMediaEventPublisher    # 默认实现：Spring 应用内事件
│   │   ├── RocketMqMediaEventPublisher # MQ 实现：turbofeed.mq.enabled=true 激活
│   │   ├── MediaUploadedEvent          # 事件载体（不可变 record）
│   │   ├── ReviewListener              # 本地事件订阅 → 审核
│   │   └── MediaReviewConsumer         # MQ 消费 → 审核（与 Listener 共用审核逻辑）
│   ├── processing/             #   图片处理链（Decorator）
│   │   ├── ImageProcessor / ThumbnailProcessor / ImageProcessingChain
│   ├── ratelimit/              #   Redis 限流器（骨架，逻辑由使用者实现）
│   │   └── UploadRateLimiter   #   tryAcquire(userId)：跨实例时间窗维度
│   ├── validation/             #   上传校验责任链（模板方法 + 不可断链）
│   │   ├── UploadValidation     #   校验上下文（格式写回 + 完成回调注册）
│   │   ├── UploadValidator       #   抽象基类（模板方法：本校验 → next）
│   │   ├── UploadValidationChain #   建链 + 入口（@Order 排序，构造期 setNext）
│   │   ├── ConcurrentUploadValidator  # 环1(Order1)：Redis SET NX EX 并发护栏
│   │   └── FileConstraintValidator    # 环2(Order2)：批量/空/大小/Magic Number
│   └── review/                 #   审核域
│       ├── MediaStatus         #   PENDING / APPROVED / REJECTED
│       └── MediaReviewService  #   状态注册表 + 唯一合法转换
├── security/                   # 认证基础设施
│   ├── JwtUtil                 #   HS256 签发/验签（零依赖手写，恒定时间比较）
│   ├── JwtAuthenticationFilter #   Bearer 验签 → ThreadLocal 绑定 → finally 清理
│   └── UserContext / UserContextHolder
├── storage/                    # 存储端口（防腐层）
│   ├── MediaStorageClient      #   接口 + StoredMedia(mediaId, url)
│   └── PlaceholderStorageClient#   占位实现（不落盘；MinIO 接入点）
├── config/                     # 配置属性类
│   ├── JwtProperties / AuthProperties / MediaProperties
│   └── SentinelRateLimitConfig # 上传限流规则注册（FlowRule×2：并发 + QPS）
└── exception/                  # BizException + GlobalExceptionHandler
    ※ sharding/ 包曾在 v0 收录自写 HashModShardingAlgorithm，现已删除改用 SS 内置 HASH_MOD（见 changelog 0010+0012）
```
> 资源侧：`src/main/resources/shardingsphere-config.yaml`（分片规则）+ `src/main/resources/db/user_schema.sql`（建表 DDL，4 物理表）。

## 2. 认证链路

```
POST /api/auth/login ──> AuthService（演示账号校验，恒定时间比较）
                          └─> JwtUtil.generateToken(userId)：HS256，载荷 sub/iat/exp
后续请求 ──> JwtAuthenticationFilter（Order = HIGHEST_PRECEDENCE + 10）
              ├─ 无令牌        → 匿名放行（是否要求登录由业务调用点决定）
              ├─ 令牌无效/过期  → 直接 401（不静默降级为匿名，安全反模式）
              └─ 验签通过      → UserContextHolder.set(userId)  [ThreadLocal]
           ──> Controller / Service
                 └─ UserContextHolder.requireUserId()   ← 业务层取身份
           finally: UserContextHolder.clear()            ← 线程池复用防串号（强制）
```

要点：

- **userId 永不出现在方法签名**，客户端无法指定他人身份；
- `JwtUtil` 的 `Clock` 可注入（单测可控时间）；生产替换为 jjwt / java-jwt + 密钥管理 + refresh token；
- 未携带令牌的公开接口（如登录本身）合法匿名；受保护接口由 `requireUserId()` 显式声明并返回 `UNAUTHORIZED(40101)`。

## 3. 上传主链路（MediaUploadService）

```
upload(files, requestId)          @SentinelResource 机器维度限流（见 §3.1）
 ├─ validationChain.validate()    责任链校验（见 §3.2）→ 返回上下文
 │   ├─ ConcurrentUploadValidator   环1：Redis SET NX EX 并发护栏（占位回调注册）
 │   └─ FileConstraintValidator     环2：批量数 → 空文件 → 大小 → Magic Number（格式写回）
 └─ for each: storeOne(userId, file, format)
     ├─ maybeProcess(file)        处理链可选：默认关闭（流式路径）；启用时全量读取
     └─ storageClient.store(...)  写存储端口 → publish(MediaUploadedEvent)
   finally: context.runCompletionCallbacks()   ← 释放并发占位（成功/失败/异常全覆盖）
```

- **校验整体外移**：批量数 / 空文件 / 大小 / Magic Number / 用户级并发护栏全部在 `service/validation` 责任链完成，新增校验规则只加链环节、主链路零改动（对扩展开放）；
- **Magic Number 嗅探**：`FileConstraintValidator` 流式 `readNBytes` 读前 16 字节（覆盖 RIFF....WEBP 偏移 8），不信任扩展名 / Content-Type；白名单 jpg / png / gif / webp，**刻意排除 SVG**（XSS）；嗅探出的真实格式经 `UploadValidation#recordFormat` 写回上下文，`storeOne` 直接取用，**不二次读文件头**；
- **内存纪律**：默认路径零全量加载（`getBytes()` 仅处理链启用时使用）；处理链开启后 ImageIO 解码 12MP ARGB ≈ 48MB/张，需评估 QPS 与堆内存；
- **处理失败降级**：处理链异常不阻断上传，回退原图；webp（无 ImageIO 编解码）与 gif（保动图）跳过处理；
- **占位释放**：并发护栏占位 key 由 `ConcurrentUploadValidator` 注册完成回调，主链路 `finally` 统一触发——校验失败 / 业务异常 / 正常返回均覆盖，TTL 兜底进程崩溃场景；
- 上传成功 = 受理进入 PENDING，**审核通过前不对公域暴露**。

### 3.2 校验责任链（service/validation）

| 环节 | @Order | 职责 | 失败码 |
|---|---|---|---|
| `ConcurrentUploadValidator` | 1 | 同用户上传并发护栏：Redis `SET key 1 NX EX ttl` 原子占位，避免校验期间并发穿透 | UPLOAD_IN_PROGRESS(42903) |
| `FileConstraintValidator` | 2 | 批量数 → 单文件（空文件 / 大小 / Magic Number）；格式写回上下文 | UPLOAD_INVALID(42902) |

设计要点：

- **模板方法防断链**：`UploadValidator` 基类 `validate` 为 final 模板方法（本校验 → 推进 next），子类只能实现 `doValidate`，无法忘记调 next（手工责任链最常见的断链事故）；
- **链推进由建链期负责**：`UploadValidationChain` 注入 `List<UploadValidator>` 按 `@Order` 排序，构造期一次性 setNext，运行期零锁零重建；
- **顺序契约**：先廉价本地校验、后外部依赖——本项目并发占位在前（先占坑防穿透），文件约束在后；
- **并发护栏 fail-open**：Redis 连接失败时放行（warn 日志）而非拒绝上传——本环节是防护性增强而非正确性依赖，Redis 故障不应阻断主链路；若升级为强护栏（如付费配额）应改 fail-closed 抛 DEPENDENCY_UNAVAILABLE；
- **TOCTOU 根治**：原实现「先 `hasKey` 再判断」存在竞态（两并发请求同时过检），改为 `SET NX EX` 单命令原子占位 + TTL 兜底过期，彻底消除竞态；
- **占位者独占删除权**：仅 NX 占位成功方注册删除回调，不存在误删他人占位的窗口。

### 3.1 限流（机器维度 Sentinel + 用户维度 Redis）

| 层 | 实现 | 维度 | 触发表现 |
|---|---|---|---|
| Sentinel 并发 | `FlowRule`（FLOW_GRADE_THREAD=20） | 单机执行中线程数 | RATE_LIMITED |
| Sentinel QPS | `FlowRule`（FLOW_GRADE_QPS=100） | 单机每秒 | RATE_LIMITED |
| Redis 用户级 | `UploadRateLimiter.tryAcquire(userId)` | 每用户时间窗（阈值 per-user，跨实例） | 由使用者实现后接入 |

- **埋点方式（注解驱动）**：`upload()` 上 `@SentinelResource(value = UPLOAD_RESOURCE, entryType = IN, blockHandler = "uploadBlocked")`——SentinelResourceAspect（SCA starter 自动装配）代理执行，entry/exit 由切面成对管理（业务抛异常也保证 exit）；规则命中回调同类 `uploadBlocked` 转 `RATE_LIMITED`；
- **`entryType` 必须显式 IN**：注解默认 OUT（依赖被调方语义），Web 入口流量是 IN——漏配不影响本接口限流，但会污染链路统计口径；
- **用户级限流为何不走 Sentinel**：注解模式热点参数（ParamFlowRule）只能取方法签名参数 `(files, requestId)`，而 userId 在 ThreadLocal——不为限流把 userId 塞回方法签名，用户维度整体移交 Redis（跨实例计数本就比单机热点规则更对）；
- **规则注册**：`SentinelRateLimitConfig` 启动时从 `MediaProperties.RateLimit` 读阈值（代码注册，克隆即跑；接 Nacos 后可换动态数据源）；
- **为什么并发与 QPS 双规则**：上传是 IO 型接口，慢存储时 QPS 规则防不住线程堆积，并发上限才是进程保护第一道闸；
- **选型说明**：网关用 `spring-boot-starter-data-redis`（Lettuce + RedisTemplate 原子命令）而非 Redisson——网关只需 INCR/EXPIRE 级能力，不需要分布式锁；Redisson 留在 tf-counter / tf-feed-engine；
- **体积维度不在 Sentinel 范围**：带宽总量由 Nginx `limit_req` + `client_max_body_size` 兜底（部署层）。

## 4. 审核流转

状态模型：`PENDING → APPROVED / REJECTED`（单路径，终态不可再流转）。

- 事件驱动：`MediaUploadedEvent` 发布后，`ReviewListener`（本地事件）或 `MediaReviewConsumer`（MQ）调用 `MediaReviewService.handleUploaded` → `submitForReview`（进入 PENDING）→ 机审占位（当前直接放行演示闭环，真实实现接内容安全 API）→ `review`；
- **存储边界**：状态存内存 `ConcurrentHashMap`，重启即丢（无 MySQL 约束下的演示态）；元数据表落地后由 DB 持久化替代；
- 前端展示侧一律过滤 `status = APPROVED`。

## 5. 事件总线

| 实现 | 激活条件 | 语义 |
|---|---|---|
| `LocalMediaEventPublisher` | 默认 | Spring 应用内事件，同步，零外部依赖 |
| `RocketMqMediaEventPublisher` | `turbofeed.mq.enabled=true` + `rocketmq.name-server` | 跨进程投递，削峰 / 解耦 / 失败重试 |

发布方只依赖 `MediaEventPublisher` 端口，新增订阅方（清理 / 通知服务）零改动发布方。

## 6. 配置参考（application.yml 全量业务键）

| 键 | 默认 | 说明 |
|---|---|---|
| `turbofeed.jwt.secret` | demo 值 | 生产必须替换为密钥管理注入的高熵值 |
| `turbofeed.jwt.expire-seconds` | 86400 | 令牌有效期（秒） |
| `turbofeed.auth.demo-users.*` | admin/123456 | 演示账号；生产改 DB + 哈希 |
| `turbofeed.media.key-prefix` | media | 对象 key 前缀（`media/{userId}/{uuid}.{ext}`） |
| `turbofeed.media.max-file-size` | 5MB | 单文件上限（业务层） |
| `turbofeed.media.max-batch-count` | 9 | 单次张数上限 |
| `turbofeed.media.public-url-base` | https://oss... | URL 拼接基址（占位） |
| `turbofeed.media.processing-enabled` | false | 图片处理链开关（Decorator：缩略图） |
| `turbofeed.media.thumbnail-max-dimension` | 2048 | 缩略图最长边 |
| `turbofeed.media.rate-limit.thread` | 20 | 上传并发线程数上限（Sentinel） |
| `turbofeed.media.rate-limit.qps` | 100 | 上传单机 QPS 上限（Sentinel） |
| `turbofeed.media.rate-limit.per-user` | 10 | 每用户限流阈值（Redis UploadRateLimiter 消费） |
| `turbofeed.media.rate-limit.inflight-ttl-seconds` | 30 | 同用户上传并发占位 TTL（秒，ConcurrentUploadValidator；进程崩溃兜底过期） |
| `spring.data.redis.host / port` | localhost:6379 | Redis 连接（Lettuce，懒连接） |
| `management.health.redis.enabled` | false | Redis 健康指示器（就绪后开启，防克隆即跑被拉 DOWN） |
| `turbofeed.mq.enabled` | false | RocketMQ 事件总线切换 |
| `spring.servlet.multipart.max-file-size` | 6MB | 框架层兜底（略高于业务限额） |
| `turbofeed.fanout.* / counter.* / hotspot.*` | — | 引擎参数（网关侧仅为占位，随引擎模块实现回收） |

## 7. 扩展点（后续接入不变更本模块契约）

| 接入项 | 落点 |
|---|---|
| MinIO | 新增 `MinioStorageClient implements MediaStorageClient`，按配置切换激活 |
| 内容安全机审 | `MediaReviewService.handleUploaded` 中 TODO 占位处 |
| 真实账号体系 | `AuthService` 校验实现替换，签发契约不变 |
| 观测性 Phase 1 | 网关新增 `TraceIdFilter`（Order 在 JwtAuthenticationFilter 之前）+ logback JSON |
| 幂等去重 | `X-Request-Id` 已透传至事件，落地时按 requestId + userId 去重 |

## 8. 分库分表（ShardingSphere）

元数据（当前为 `user` 表）按 `id`（用户全局唯一 ID，雪花 Long）分库分表，路由对业务 SQL 透明。完整设计与演进见 `docs/architecture/sharding.md`。

- **启用方式**：`application.yml` 的 `spring.datasource.url=jdbc:shardingsphere:classpath:shardingsphere-config.yaml` + `driver-class-name=org.apache.shardingsphere.driver.ShardingSphereDriver`；真实数据源与规则在 `shardingsphere-config.yaml` 内定义（驱动原生模式，无需 spring-boot-starter）；
- **分片方案**：2 库（ds_0→turbo_feed_1 / ds_1→turbo_feed_2）× 2 表（user_0 / user_1）= 4 物理表；`actualDataNodes: ds_${0..1}.user_${0..1}`；
- **算法选型**：Apache ShardingSphere 5.5.3 **内置 HASH_MOD**（`org.apache.shardingsphere.sharding.algorithm.sharding.mod.HashModShardingAlgorithm`），库/表各一条算法实例，由 `props.sharding-count` 显式指定候选数（2）。`actualDataNodes` 必须写成 `ds_${0..1}.user_${0..1}` 与内置前缀匹配工具对接（DataNodeInfo.getPrefix + 0-padded 下标）；
- **为什么不自写**：5.5.x 接口 `StandardShardingAlgorithm extends ShardingAlgorithm extends ShardingSphereAlgorithm extends TypedSPI`，从 `TypedSPI` 继承的仅 `default init(java.util.Properties)` 与 `Object getType()`，**不存在 `getProps()`**，自写类极易踩编译错；且 SS 内置算法的 `hash(value) % shardingCount` 与 `ShardingAutoTableAlgorithmUtils.findMatchedTargetName` 拼接逻辑，业务上无自写必要，详见 `docs/architecture/sharding.md §4`；
- **范围查询广播**：取模分片无键序，`RangeShardingValue` 返回全部候选目标由 ShardingSphere 合并（空集会让 `BETWEEN/>/<` 静默空结果）；
- **建表 DDL**：`src/main/resources/db/user_schema.sql`（4 物理表 + 跨分片唯一性坑说明）；
- **版本**：`shardingsphere-jdbc:5.5.3`（pom 显式锁定；⚠️ 正式坐标是 `shardingsphere-jdbc`，**不是** `shardingsphere-jdbc-core`——后者不在 Maven Central，会导致 `import org.apache.shardingsphere.*` 全军报红）。**5.5.3 vs 5.5.0**：5.5.0 的 `shardingsphere-test-util` 依赖未发布到 Maven Central，编译/解析直接失败；Apache PR #31143 已在 5.5.1+ 修复，5.5.3 为当前最新稳定版（2026-02-23 发布）。

> 历史坑（已分两步清除）：
> - **changelog 0010**：v0 分片代码从优惠券开源项目整段复制——config 引用 `com.nageoffer.onecoupon...` 算法类、表是 `t_coupon_template`、`shop_number` 分片键；且 `pom` 缺依赖、`url`/`driver-class-name` 写反、`DBShardingUtil` 经 Hutool `Singleton` 取实例导致 `shardingCount=0` 除零。整体重写为自写 `HashModShardingAlgorithm` + turbo-feed `user` 表。
> - **changelog 0012**：自写类 `@Override getProps()` 编译失败——5.5.0 接口层级里 `getProps()` 根本不存在；且 SS 已经内置同名 `HASH_MOD` 算法。删除自写类，改用 `type: HASH_MOD` 内置算法 + `props.sharding-count`，更简洁也更贴合 SS 演进。
