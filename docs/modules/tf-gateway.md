# tf-gateway 技术文档

> HTTP 接入层：唯一对外暴露的业务端口。承载登录鉴权 / RBAC、UGC 图文上传（一帖多图，审核后展示）、
> 审核状态机与举报申诉闭环、评论、公域 Feed 转发（转发给 tf-feed-engine）。
> 父包 `com.turbofeed.gateway`，独立 Spring Boot 服务，默认端口 8080。

## 1. 代码结构

```
com.turbofeed.gateway
├── GatewayApplication          # 启动类（@SpringBootApplication，仅扫 gateway 包）
├── controller/                 # 协议适配层（薄）：参数绑定 + 统一返回结构
│   ├── AuthController / RegisterController      # 登录、注册（按手机号）
│   ├── MediaController                          # 上传 / 我的内容 / 状态 / 删除 / 描述
│   ├── MediaReportController                    # 举报 / 申诉
│   ├── CommentController                        # 评论发布 / 列表 / 点赞
│   ├── FeedController                           # 公域推荐流（转发引擎）
│   ├── AdminMediaController                     # 待审队列 / 审核 / 举报复核 / 申诉复核
│   └── AdminSensitiveWordController             # 敏感词库增删改 + 热加载
├── service/                    # 业务层
│   ├── AuthService             #   按手机号登录校验（读 user 表）+ JWT 签发（携带 role）
│   ├── UserService             #   注册（雪花 ID）+ 密码哈希
│   ├── MediaUploadService      #   上传主链路（校验责任链 → 落存储 → 落库整帖 → 发事件）
│   ├── ImageFormat             #   Magic Number 格式判定（白名单）
│   ├── event/                  #   上传事件总线（Observer）
│   │   ├── MediaEventPublisher         # 端口：发布抽象
│   │   ├── LocalMediaEventPublisher    # 默认：Spring 应用内事件（@Async）
│   │   ├── RocketMqMediaEventPublisher # MQ：turbofeed.mq.enabled=true 激活
│   │   ├── MediaUploadedEvent          # 事件载体（含 postId / mediaIds / urls / caption）
│   │   ├── ReviewListener              # 本地事件订阅 → 审核
│   │   └── MediaReviewConsumer         # MQ 消费 → 审核（与 Listener 共用审核逻辑）
│   ├── feed/                   #   公域时间线投递（端口 + 双实现）
│   │   ├── FeedTimelinePublisher        # 端口：append / remove
│   │   ├── HttpFeedTimelinePublisher    # mq.enabled=false：同步 HTTP 调引擎
│   │   └── RocketMqFeedTimelinePublisher# mq.enabled=true ：RocketMQ 顺序消息（fail-fast）
│   ├── processing/             #   图片处理链（Decorator）：ImageProcessor / ThumbnailProcessor
│   ├── ratelimit/              #   用户维度限流（Redis 时间窗）+ LocalFallbackRateLimiter
│   ├── idempotency/            #   UploadIdempotency：X-Request-Id 去重
│   ├── moderation/             #   内容安全（文本）：五层架构，见 architecture/content-security-design.md
│   │   ├── ContentSecurityService     #   ①统一入口 + ④决策层（长度/归一化/匹配/白名单/动作/计数）
│   │   ├── ContentScene/ModerationAction/ContentVerdict/SensitiveWordHit  # ①契约层
│   │   ├── TextNormalizer             #   ②预处理：NFKC→剥不可见→剥分隔符→小写→繁简（带下标回溯）
│   │   ├── AhoCorasick                #   ③匹配：多模自动机（不可变快照，原子引用热切换）
│   │   ├── SensitiveWordService        #   ③词库热加载（AC + 词→分类同一原子引用）
│   │   ├── WhitelistService/Repository/Entry  # ④白名单（场景 × 主体两维度豁免）
│   │   └── ContentSecurityProperties  #   turbofeed.content-security.*（不含任何词条）
│   ├── validation/             #   上传校验责任链（模板方法 + 不可断链）
│   │   ├── UploadValidation     #   校验上下文（格式写回 + 完成回调注册）
│   │   ├── UploadValidator      #   抽象基类（模板方法：本校验 → next）
│   │   ├── UploadValidationChain#   建链 + 入口（@Order 排序，构造期 setNext）
│   │   ├── ConcurrentUploadValidator  # 环1(Order1)：Redis SET NX EX 并发护栏
│   │   └── FileConstraintValidator    # 环2(Order2)：批量/空/大小/Magic Number
│   ├── query/                  #   MediaQueryService（我的内容 / 状态 / 推荐流聚合）+ MediaItem
│   ├── comment/                #   CommentService / CommentJdbcRepository（按 media_id 分片）
│   └── review/                 #   审核域
│       ├── MediaStatus         #   PENDING / APPROVED / REJECTED / DELETED / TAKEN_DOWN / APPEALING
│       ├── MediaReviewService  #   状态机入口 + 举报/申诉闭环（CAS 流转）
│       ├── ContentModeration / ContentModerationRouter / RuleBasedModeration
│       ├── AutoPassModeration / AiContentModeration   # 占位与预留实现
│       └── credit/             #   AccountCreditService / CreditLevel（信用分级 L0/L1/L2 + 新人观察期）
├── security/                   # 认证与授权基础设施
│   ├── JwtUtil / JwtAuthenticationFilter / UserContext / UserContextHolder
│   ├── Permission / Role        # RBAC：权限枚举 + 角色→权限映射
│   └── RequirePermission / PermissionInterceptor
├── repository/                 # JDBC 持久层（经 ShardingSphere 路由到分片）
│   ├── MediaJdbcRepository      # media 行/帖读写（含 updateStatusCas 与有界重试）
│   ├── UserJdbcRepository / AccountCreditRepository / ReportRepository / AppealRepository
├── storage/                    # 存储端口（防腐层）
│   ├── MediaStorageClient       # 接口 + StoredMedia(mediaId, url)
│   ├── MinioStorageClient       # storage=minio（当前默认）
│   ├── LocalDiskStorageClient   # storage=local
│   └── PlaceholderStorageClient # storage=placeholder（仅拼 URL 不落盘）
├── client/                     # 跨服务客户端（防腐层）
│   ├── FeedEngineClient         # 调 tf-feed-engine /internal/feed/**
│   └── FeedItemMapper           # MediaItem ⇄ FeedItemView 契约映射
├── config/                     # 配置属性类 + SnowflakeConfig（雪花 ID，缺失即启动失败）
├── domain/User · exception/ · util/（CaptionMarkParser / SnowflakeIdGenerator）
└── ※ sharding/ 包曾在 v0 收录自写 HashModShardingAlgorithm，现已删除改用 SS 内置 HASH_MOD（见 changelog 0010+0012）
```
> 资源侧：`shardingsphere-config.yaml`（分片规则，6 张逻辑表）+ `shardingsphere-config-128.yaml`（扩容版）+ `db/{user_schema,media_schema,expand_media_tables_128,rebalance_media_128}.sql`。
> ⚠️ 分片相关脚本一律遵循 **autoTables 全局连续编号**（`ds_0` 拿偶数下标、`ds_1` 拿奇数下标），
> 不是「每库从 0 重新编号」——物理表名写错会静默路由不到（见 changelog 0041）。

## 2. 认证与授权（JWT + RBAC）

```
POST /api/auth/login ──> AuthService（按手机号查 user 表，恒定时间比较密码哈希）
                          └─> JwtUtil.generateToken(userId, role)：HS256，载荷 sub/role/iat/exp
后续请求 ──> JwtAuthenticationFilter（Order = HIGHEST_PRECEDENCE + 10）
              ├─ 无令牌        → 匿名放行（是否要求登录由业务调用点决定）
              ├─ 令牌无效/过期  → 直接 401（不静默降级为匿名，安全反模式）
              └─ 验签通过      → UserContextHolder.set(userId, role)  [ThreadLocal]
           ──> Controller / Service
                 └─ UserContextHolder.requireUserId()             ← 业务层取身份
           ──> @RequirePermission(...) ──> PermissionInterceptor   ← 按权限校验
                 └─ 无权限 → FORBIDDEN(40301)
           finally: UserContextHolder.clear()            ← 线程池复用防串号（强制）
```

要点：

- **userId 永不出现在方法签名**，客户端无法指定他人身份；
- **角色来自 `user` 表 `role` 列**（真 RBAC），登录时读库派生进 JWT；配置里**不维护任何手机号白名单**（工程规范：默认配置不写 demo / 敏感字面量）；
- **按权限校验而非按角色名**：接口用 `@RequirePermission(CONTENT_REVIEW)` 声明所需权限，`Role` 枚举绑定权限集。
  新增角色 = 加枚举值 + 一行权限映射，业务接口零改动；
- 权限枚举：`CONTENT_REVIEW` / `CONTENT_TAKEDOWN` / `DASHBOARD_VIEW` / `USER_MANAGE` / `SYSTEM_CONFIG` / `CREDIT_MANAGE`；
  角色映射：`USER`=无、`REVIEWER`=前三个、`ADMIN`=全部。**未知角色编码一律降级 `USER`**（最小权限）；
- `JwtUtil` 的 `Clock` 可注入（单测可控时间）；生产替换为 jjwt / java-jwt + 密钥管理 + refresh token；
- 公开接口（登录 / 注册 / 公域推荐流 / 评论列表）合法匿名；受保护接口由 `requireUserId()` 显式声明并返回 `UNAUTHORIZED(40101)`。
- `AdminAuthInterceptor` 保留在包内但**已被 `PermissionInterceptor` 取代**（原实现只放行 ADMIN，无法表达「审核员可审不可管」）。

## 3. 上传主链路（MediaUploadService，一帖多图）

```
upload(files[], caption, requestId)     @SentinelResource 机器维度限流（见 §3.1）
 ├─ 幂等：X-Request-Id 命中 → 返回首次结果（UploadIdempotency）
 ├─ validationChain.validate()          责任链校验（见 §3.2）→ 返回上下文
 │   ├─ ConcurrentUploadValidator   环1：Redis SET NX EX 并发护栏
 │   └─ FileConstraintValidator     环2：批量数 → 空文件 → 大小 → Magic Number（格式写回）
 ├─ 内容安全：caption 走 ContentSecurityService（场景 CAPTION：长度 → 归一化 → AC 匹配
 │   → 白名单 → 分类动作），命中即 fail-closed 拒绝（42904）
 ├─ for each: storeOne(userId, file, format) → storageClient.store(...)
 │   └─ 中途任一张失败 → hardDeleteByPost 补偿清理已落库残行
 ├─ 落库整帖：N 条 media 行共享同一 postId，seq = 0..N-1
 └─ publish(MediaUploadedEvent{postId, mediaIds, urls, caption, ...})
   finally: context.runCompletionCallbacks()   ← 释放并发占位（成功/失败/异常全覆盖）
```

**一帖多图模型**：一次上传批次 = 一个 `post_id`，`seq` 为 0 起帖内序号（决定轮播顺序）；
**分片键仍是 `user_id`**，因此同帖的 N 张图必然落在同一分片，帖内查询永远单分片精准命中。
历史数据 `post_id = ''` 视为单图帖、帖身份回退 `media_id`，**零迁移**，新旧数据走同一段代码。

**为什么中途失败要物理清理**：留下的「缺 `seq=0` 代表行 / 张数不齐」的半成品帖，既不会出现在「我的内容」
（代表行不存在），也无法被用户删除，是最难排查的一类脏数据。

其他要点：

- **校验整体外移**：批量数 / 空文件 / 大小 / Magic Number / 并发护栏全在 `service/validation` 责任链完成，新增规则只加链环节、主链路零改动；
- **Magic Number 嗅探**：`FileConstraintValidator` 流式 `readNBytes` 读前 16 字节（覆盖 `RIFF....WEBP` 偏移 8），不信任扩展名 / Content-Type；白名单 jpg / png / gif / webp，**刻意排除 SVG**（XSS）；嗅探出的真实格式经 `UploadValidation#recordFormat` 写回上下文，`storeOne` 直接取用，**不二次读文件头**；
- **内存纪律**：默认路径零全量加载（`getBytes()` 仅处理链启用时使用）；处理链开启后 ImageIO 解码 12MP ARGB ≈ 48MB/张，需评估 QPS 与堆内存；
- **处理失败降级**：处理链异常不阻断上传，回退原图；webp（无 ImageIO 编解码）与 gif（保动图）跳过处理；
- **描述解析**：`CaptionMarkParser` 解析抖音式文案（`@用户` / `#话题` / `[image:idx:filename]`），原始文本存 `caption`、解析结果冗余存 `caption_mark`（前端只读消费）；
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
- **并发护栏 fail-open**：Redis 连接失败时放行（warn 日志）而非拒绝上传——本环节是防护性增强而非正确性依赖；若升级为强护栏（如付费配额）应改 fail-closed 抛 `DEPENDENCY_UNAVAILABLE`；
- **TOCTOU 根治**：原实现「先 `hasKey` 再判断」存在竞态（两并发请求同时过检），改为 `SET NX EX` 单命令原子占位 + TTL 兜底过期；
- **占位者独占删除权**：仅 NX 占位成功方注册删除回调，不存在误删他人占位的窗口。

### 3.1 限流（机器维度 Sentinel + 用户维度 Redis）

| 层 | 实现 | 维度 | 触发表现 |
|---|---|---|---|
| Sentinel 并发 | `FlowRule`（FLOW_GRADE_THREAD=20） | 单机执行中线程数 | RATE_LIMITED(42901) |
| Sentinel QPS | `FlowRule`（FLOW_GRADE_QPS=100） | 单机每秒 | RATE_LIMITED(42901) |
| Redis 用户级 | `UploadRateLimiter.tryAcquire(userId)` | 每用户时间窗（阈值 per-user，跨实例） | RATE_LIMITED(42901) |

- **埋点方式（注解驱动）**：`upload()` 上 `@SentinelResource(value = UPLOAD_RESOURCE, entryType = IN, blockHandler = "uploadBlocked")`——SentinelResourceAspect（SCA starter 自动装配）代理执行，entry/exit 由切面成对管理（业务抛异常也保证 exit）；规则命中回调同类 `uploadBlocked` 转 `RATE_LIMITED`；
- **`entryType` 必须显式 IN**：注解默认 OUT（依赖被调方语义），Web 入口流量是 IN——漏配不影响本接口限流，但会污染链路统计口径；
- **用户级限流为何不走 Sentinel**：注解模式热点参数（ParamFlowRule）只能取方法签名参数，而 userId 在 ThreadLocal——不为限流把 userId 塞回方法签名，用户维度整体移交 Redis（跨实例计数本就比单机热点规则更对）；
- **规则注册**：`SentinelRateLimitConfig` 启动时从 `MediaProperties.RateLimit` 读阈值（代码注册，克隆即跑；接 Nacos 后可换动态数据源）；
- **为什么并发与 QPS 双规则**：上传是 IO 型接口，慢存储时 QPS 规则防不住线程堆积，并发上限才是进程保护第一道闸；
- **选型说明**：网关用 `spring-boot-starter-data-redis`（Lettuce + RedisTemplate 原子命令）而非 Redisson——只需 INCR/EXPIRE 级能力，不需要分布式锁；Redisson 仅声明在 tf-counter（尚未实现），网关与引擎都不加载；
- **体积维度不在 Sentinel 范围**：带宽总量由 Nginx `limit_req` + `client_max_body_size` 兜底（部署层）。

## 4. 审核状态机

状态集合：`PENDING → APPROVED / REJECTED`（发布前判定）；发布后可被 `TAKEN_DOWN`；`APPEALING` 为申诉中间态；`DELETED` 为用户删除。

**持久化**：状态落在 **MySQL `media` 表 `status` 列**（`MediaJdbcRepository`），**不是内存态**，重启不丢失；
整帖各行状态一致（整帖一审）。

```
上传事件 handleUploaded(event)
 ├─ 兜底落库整帖 PENDING（media_id 主键幂等，重投安全）
 ├─ 机审初筛（整帖一次）：ContentModerationRouter 按 moderation-mode 选实现
 │    rule=RuleBasedModeration（默认，本地规则引擎·文件名/元数据级 fail-closed）
 │    pass=AutoPassModeration（占位恒通过） / ai=AiContentModeration（预留，视觉模型）
 ├─ 机审 REJECTED → 整帖 REJECTED（不进人工队列）
 └─ 机审通过/降级 → 按账号信用分级分流：
      L0（新人观察期 / 违规加严 / 低信用）→ 先审后放：留在 PENDING 等人审终裁
      L1 / L2（普通 / 高信用）           → 先发后审：整帖直接 APPROVED，进对应流量池，靠举报/人审兜底
```

> ⚠️ **新账号默认走先审后放**（`0027` 起）：`AccountCreditRepository.ensure` 为新账号写入
> `new_user_watch = 1`，`getLevel` 据此返回 **L0**，内容停在 PENDING 等人审。累计**人工**通过
> `turbofeed.media.review.new-user-approve-threshold`（默认 3）帖后自动转正，之后按 `level` 正常分级。
> 先发后审的自动通过**不计数**——否则新号第一帖上传就会把自己顶出观察期。
>
> **两条加严路径分列存储**：`strict_queue_flag`（违规加严，无自动解除）与 `new_user_watch`
> （新人观察期，达标自动解除）**不复用同一列**，否则「新人转正」会顺手解封被处罚账号。
>
> 历史行为（`0027` 之前）为「无信用记录默认 L1 → 新号内容直接进公域」，与设计意图相反，已修正；
> 背景、取舍与端到端验证见 [`docs/changelog/0027`](../changelog/0027-new-user-watch-period.md)。

**管理员审核入口**：`reviewByMediaId → review(mediaId, userId, approved) → ReviewOutcome(status, transitioned)`。
人工通过分支额外调用 `AccountCreditService#onHumanApproved` 累加新人观察期计数（**只有人工路径计数**）。

- **CAS 流转**：`UPDATE media SET status=? WHERE user_id=? AND post_id=? AND status=期望前置态`，
  用影响行数判定是否真正流转；0 行 = 已被另一条路径流转 → **幂等返回，且不重复投递时间线**；
- **有界重试**：整帖多行 UPDATE 在二级索引 `idx_user_post` 与聚簇主键 `PRIMARY` 间往返加锁，
  加锁次序相反时构成 InnoDB 死锁 → 捕获 `PessimisticLockingFailureException` 重试 3 次（15ms×attempt 退避），
  耗尽后原样抛出（不做无界重试掩盖问题）；
- **为什么投递要看 `transitioned` 而不是只看状态**：造「结果状态 == APPROVED」会把「CAS 落空」误判成「我审的」，同一条帖子被投递两次；
- **同类 TOCTOU 已一并收敛**：高危举报下架、管理员确认举报（防信用重复扣减）、作者申诉（防重复申诉单）、申诉翻案（防重复投递 + 信用重复加回）。

**举报 / 申诉闭环**：

| 动作 | 前置状态 | 结果 |
|---|---|---|
| 用户举报（高危理由） | `APPROVED` | 整帖 `TAKEN_DOWN` + 移出时间线 + 扣作者信用 |
| 用户举报（普通理由） | `APPROVED` | 写 `report` 表，进人工队列 |
| 管理员确认举报 | `APPROVED` | 整帖 `TAKEN_DOWN` + 扣信用（CAS 保证只扣一次） |
| 作者申诉 | `REJECTED` / `TAKEN_DOWN` | 整帖 `APPEALING`（暂不可见）+ 写 `appeal` 表 |
| 管理员申诉翻案 / 维持 | `APPEALING` | `APPROVED`（按信用池恢复公域 + 信用加回）/ `TAKEN_DOWN` |

## 5. 事件总线与 Feed 投递

两条**互相独立**的投递链，都由端口 + 双实现构成，按 `turbofeed.mq.enabled` 互斥装配：

| 投递链 | 端口 | 默认（mq=false） | mq=true |
|---|---|---|---|
| 上传事件（触发审核） | `MediaEventPublisher` | `LocalMediaEventPublisher`（Spring 应用内事件，`@Async`，`CallerRunsPolicy` 背压） | `RocketMqMediaEventPublisher` |
| 公域时间线（入流/下架） | `FeedTimelinePublisher` | `HttpFeedTimelinePublisher`（同步 HTTP 调引擎） | `RocketMqFeedTimelinePublisher`（顺序消息，**fail-fast**） |

- **MQ 连接配置放独立 profile** `application-mq.yml`（`--spring.profiles.active=mq`）：
  rocketmq-spring 的 `@ConditionalOnProperty` **无 havingValue**，属性「存在但为空」也算命中、
  仍会创建并 start 生产者 Bean，只能靠 profile 让属性**真正缺席**；
- **时间线投递的顺序性不可妥协**：`append` / `remove` 共用同一 topic 与同一消费者、靠消息体 `action` 区分，
  `syncSendOrderly(hashKey = timelineKey)` + `ConsumeMode.ORDERLY`；拆 tag / 拆消费者会让同帖的
  append 与 remove 落不同队列而失去顺序，出现「已下架内容重新出现」的内容安全事故；
- 发布方只依赖端口，新增订阅方零改动。

## 6. 持久化与分片（ShardingSphere）

元数据经 **ShardingSphere 5.5.3** 路由到分片，业务 SQL 透明。完整设计与演进见 `docs/architecture/sharding.md`。

- **启用方式**：`spring.datasource.url=jdbc:shardingsphere:classpath:shardingsphere-config.yaml` + `driver-class-name=org.apache.shardingsphere.driver.ShardingSphereDriver`（驱动原生模式，无需 starter）；
- **物理拓扑**：`sharding-count = 4` = 2 库（ds_0 → turbo_feed_1 / ds_1 → turbo_feed_2）× 2 表，
  物理表命名 `<逻辑表>_<0..3>`（`user_0..user_3`、`media_0..media_3` …）由 SS 推断；
- **6 张逻辑表与分片键**（**分片键选择的原则：让最高频查询单分片命中**）：

  | 逻辑表 | 分片键 | 理由 |
  |---|---|---|
  | `user` | `id` | 用户全局唯一 ID（雪花 Long），登录按 id 精确查 |
  | `media` | `user_id` | 「我的内容」`WHERE user_id=?` 精准命中；同用户的 user 行与 media 行落同片 |
  | `account_credit` | `user_id` | 信用读写均 `WHERE user_id=?`，与 user/media 同片 |
  | `report` | `media_id` | 举报按 media_id 插入 / 复核，单分片命中 |
  | `appeal` | `media_id` | 同上 |
  | `comment` | `media_id` | 与 media 按 user_id 分片**正交**：读某内容下全部评论单分片命中，不依赖 media 所在库 |

- **算法选型**：内置 **HASH_MOD**（`props.sharding-count: 4`）。`actualDataNodes` 必须与 `ds_${0..1}.xxx_${0..1}` 前缀模式匹配；
- **为什么不自写**：5.5.x 的 `StandardShardingAlgorithm` 继承链上**不存在 `getProps()`**，自写类极易踩编译错；且内置算法的 `hash % shardingCount` 与 `findMatchedTargetName` 拼接逻辑业务上无自写必要；
- **HASH_MOD 只能用于 `autoTables`**：手动 `tables` 引用会报 “tables sharding configuration can not use auto sharding algorithm”；
- **范围查询广播**：取模分片无键序，`RangeShardingValue` 返回全部候选目标由 SS 合并（空集会让 `BETWEEN/>/<` 静默空结果）；
- **无分片键的查询就是广播**：`listApprovedGlobal`（公域）与 `listPendingGlobal`（审核队列）不携带分片键，
  会路由到全部 4 个分片并合并——**仅适用于演示/小数据量**。生产公域流必须由推荐服务 + 异构索引提供（现由引擎时间线 ZSET 承担），审核队列应由审核中台 + 独立索引提供；
- **版本**：`shardingsphere-jdbc:5.5.3`（pom 显式锁定；⚠️ 正式坐标是 `shardingsphere-jdbc`，**不是** `shardingsphere-jdbc-core`——后者不在 Maven Central，会导致 `import org.apache.shardingsphere.*` 全军报红）。

> 历史坑（已分两步清除）：
> - **changelog 0010**：v0 分片代码从优惠券开源项目整段复制——config 引用 `com.nageoffer.onecoupon...` 算法类、表是 `t_coupon_template`、`shop_number` 分片键；且 `pom` 缺依赖、`url`/`driver-class-name` 写反、`DBShardingUtil` 经 Hutool `Singleton` 取实例导致 `shardingCount=0` 除零。整体重写为自写 `HashModShardingAlgorithm` + turbo-feed `user` 表。
> - **changelog 0012**：自写类 `@Override getProps()` 编译失败——5.5.0 接口层级里 `getProps()` 根本不存在；且 SS 已经内置同名 `HASH_MOD` 算法。删除自写类，改用 `type: HASH_MOD` + `props.sharding-count`。

## 7. 配置参考（application.yml 业务键）

| 键 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8080 | 服务端口 |
| `turbofeed.jwt.secret` | `${TURBOFEED_JWT_SECRET:}` | **无默认值，缺失或长度 < 32 即启动失败**（`JwtProperties#requireSecret`）。HS256 是对称算法，持有密钥即可签发任意 userId + role，故拒绝任何兜底字面量。类字段亦无默认值——否则占位符缺失时会回落到字段初始值，等于没改 |
| `turbofeed.jwt.expire-seconds` | 86400 | 令牌有效期（秒） |
| `turbofeed.snowflake.worker-id` / `datacenter-id` | `${TURBOFEED_SNOWFLAKE_WORKER_ID:}` 等 | **无默认值，缺失即启动失败**（`SnowflakeConfig#requireInstanceId`）。取值 0~31，多实例必须逐实例不同，否则同毫秒生成相同 ID 撞主键 |
| `turbofeed.media.key-prefix` | media | 对象 key 前缀（`media/{userId}/{uuid}.{ext}`） |
| `turbofeed.media.max-file-size` | 5MB | 单文件上限（业务层） |
| `turbofeed.media.max-batch-count` | 9 | **单次上传张数上限（一帖多图）** |
| `turbofeed.media.public-url-base` | http://127.0.0.1:9000/turbo-feed-media/ | URL 拼接基址（指向对象存储 / CDN；bucket 需匿名可读，否则浏览器 403） |
| `turbofeed.media.storage` | minio | 存储实现：`minio` / `local` / `placeholder`（三者互斥装配） |
| `turbofeed.media.minio.*` | endpoint/accessKey/secretKey/bucket | 对象存储连接（**accessKey/secretKey 已外部化，仅 ENV 注入，缺失即启动失败**） |
| `turbofeed.media.local-dir` | ./data/media | `storage=local` 时的落盘根目录 |
| `turbofeed.media.processing-enabled` | false | 图片处理链开关（Decorator：缩略图） |
| `turbofeed.media.rate-limit.thread` / `.qps` | 20 / 100 | 上传机器维度限流（Sentinel） |
| `turbofeed.media.rate-limit.per-user` | 10 | 每用户限流阈值（Redis UploadRateLimiter） |
| `turbofeed.media.rate-limit.inflight-ttl-seconds` | 30 | 同用户上传并发占位 TTL（进程崩溃兜底） |
| `turbofeed.media.review.auto-pass` | false | **演示占位**：机审结果直接放行（仅本地联调）。生产务必 false |
| `turbofeed.media.review.moderation-mode` | rule | 机审策略：`rule` / `pass` / `ai`（预留）/ `cloud`（预留） |
| `turbofeed.media.review.banned-keywords` | 涉黄/暴恐/涉政/赌博 | 本地规则引擎关键词 |
| `turbofeed.media.review.new-user-approve-threshold` | 3 | 新人观察期解除阈值：新号累计**人工**通过该帖数后转正常分级（先发后审的自动通过不计入） |
| `turbofeed.content-security.enabled` | true | 文本检测总开关（怀疑误杀时快速回滚用） |
| `turbofeed.content-security.fail-startup-on-empty-dictionary` | `${TURBOFEED_FAIL_ON_EMPTY_DICT:false}` | 词库为空时是否拒绝启动；默认 false 保"克隆即跑"，**生产应置 true**（否则过滤静默失效无人察觉） |
| `turbofeed.content-security.scene-max-length` | NICKNAME 32 / CAPTION 2048 / COMMENT 1024 | 场景长度上限，与 DB 列宽、前端上限同一事实源 |
| `turbofeed.content-security.normalize.*` | 均 true | 归一化：总开关 / 剥分隔符 / 繁简折叠（抗绕过，见设计文档 §4） |
| `turbofeed.content-security.whitelist-enabled` | true | 白名单（误杀治理出口；**剥分隔符必须与其配合使用**） |
| `turbofeed.content-security.category-actions` / `default-action` | `{}` / BLOCK | 分类 → 动作；留空即统一 BLOCK（与改造前行为一致）。`REVIEW`/`LIMIT` 当前分别等价 BLOCK/PASS |
| `turbofeed.feed.engine.base-url` | http://localhost:8083 | 引擎地址（当前**直连**，无注册中心） |
| `turbofeed.feed.engine.connect-timeout` / `read-timeout` | 500ms / 2s | **同步调用必须设超时**：引擎故障时无限等待会耗尽网关线程池（雪崩入口） |
| `turbofeed.feed.degraded-mode` | empty | 引擎不可用时：`empty`（返回空 + WARN，**拒绝跨分片广播**，生产必须）/ `local-scan`（回源广播，仅演示） |
| `turbofeed.mq.enabled` | false | 事件总线与时间线投递的 MQ 切换（需同时激活 `mq` profile） |
| `turbofeed.media.mq.topic` / `consumer-group` | turbofeed-media-uploaded / turbofeed-media-review-group | 上传事件主题与消费组（发布端与消费端必须一致） |
| `spring.data.redis.*` | localhost:6379 / `${TURBOFEED_REDIS_PASSWORD:}` | Lettuce + 连接池；口令外部化，未注入而 Redis 要求鉴权时直接 NOAUTH |
| `management.health.redis.enabled` | false | Redis 健康指示器（就绪后开启，防「克隆即跑」被拉 DOWN） |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | 6MB / 60MB | 框架层兜底（略高于业务限额） |
| `turbofeed.fanout.* / counter.* / hotspot.*` | — | 引擎 / 计数 / 热点参数（网关侧仅为占位，随对应模块实现回收） |

## 8. 扩展点（后续接入不变更本模块契约）

| 接入项 | 落点 |
|---|---|
| 云内容安全机审 | 现 `ContentModerationRouter` 已预留 `cloud` 分支，接腾讯云/阿里云内容安全 API 时新增实现类 |
| 真实账号体系 | `AuthService` 校验实现替换，签发契约不变（现已是「DB + 角色列」，替换点只剩密码哈希算法与登录凭证形态） |
| 服务注册与发现 | `FeedEngineProperties.baseUrl` 直连 → Nacos + 客户端负载均衡，`FeedEngineClient` 构造处换 LoadBalanced builder |
| 跨服务鉴权 | `/internal/**` 当前依赖内网信任，需补服务间令牌 / mTLS |
| 观测性 | 网关新增 `TraceIdFilter`（Order 在 JwtAuthenticationFilter 之前）+ logback JSON |
