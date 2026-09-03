# 0006 - 上传接口 Sentinel 限流 + 引入 Redis（含用户级限流骨架）

## 变更类型

`feat(gateway)`：流量防护落地 + Redis 基础设施引入。

## 动机

- 上传是全系统资源消耗最重的接口（5MB×9 张/请求；处理链开启时 48MB 堆/张），且与登录、审核同进程——被刷即全站故障面；
- 已有的 ≤5MB/≤9 张是**单次请求**约束，防不住「单位时间总量」滥用；
- `ErrorCode.RATE_LIMITED(42901)` 自错误码体系建立起即为该场景预留，本次闭环。

## 改动清单

| 文件 | 改动 |
|---|---|
| `tf-gateway/pom.xml` | 新增 `spring-boot-starter-data-redis`（Lettuce，Boot BOM 托管） |
| `application.yml` | `spring.data.redis.*` 连接配置；`turbofeed.media.rate-limit.*`；`management.health.redis.enabled: false`（保克隆即跑） |
| `config/MediaProperties.java` | 新增 `RateLimit` 嵌套配置（thread / qps / perUser） |
| `config/SentinelRateLimitConfig.java` | **新增**：启动注册 FlowRule×2（并发+QPS）+ ParamFlowRule×1（userId 热点参数） |
| `service/MediaUploadService.java` | `upload()` 手动 `SphU.entry("media:upload", IN, 1, userId)`，BlockException → `RATE_LIMITED`；exit 收 finally |
| `service/ratelimit/UploadRateLimiter.java` | **新增**：Redis 限流骨架，`tryAcquire(userId)` 恒放行 + TODO（逻辑由使用者实现） |
| `docs/modules/tf-gateway.md` | §3.1 限流章节、代码结构树、配置表同步 |

## 设计要点

- **userId 不进方法签名**：从 `UserContextHolder` 取出后作为参数传给 `SphU.entry`，用户级热点规则按参数计数；
- **并发 + QPS 双规则**：IO 型接口慢存储时 QPS 防不住线程堆积，并发上限是进程保护第一道闸；
- **网关选 RedisTemplate 而非 Redisson**：只需 INCR/EXPIRE 级原子命令，不带分布式锁重依赖；
- **Lettuce 懒连接**：本地无 Redis 应用照常启动；`management.health.redis.enabled` 默认关闭防止 health 被拉 DOWN；
- **Sentinel 与 Redis 互补**：Sentinel = 单机秒级频次；Redis = 跨实例时间窗（时/日级配额）。

## 影响面

- 上传接口被限流时返回既有错误结构 `RATE_LIMITED(42901)`，前端无需变更；
- 不触碰认证 / 存储 / 审核 / 事件链路；
- 新依赖仅 `spring-boot-starter-data-redis`（BOM 托管版本，无版本仲裁风险）。

## 验证

- `xmllint` pom 通过；规则注册为 `@PostConstruct` 代码注册（无外部文件依赖）；
- 待本机 `mvn -pl tf-gateway -am compile` 后验证：快速连续上传 >10 次应触发 RATE_LIMITED（Sentinel 用户级）；启动日志含「Sentinel 上传限流规则已加载」。

## 回滚

删除 `SentinelRateLimitConfig` / `UploadRateLimiter` 两类 + 还原 `MediaUploadService.upload` + 移除 pom redis 依赖与 yml 配置段。
