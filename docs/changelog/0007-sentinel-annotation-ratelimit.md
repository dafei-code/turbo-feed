# 0007 - 上传限流改 @SentinelResource 注解驱动

## 变更类型

`refactor(gateway)`：SphU 手动埋点 → 注解声明式限流（行为等价，用户级规则归属调整）。

## 动机

- 编程式 `SphU.entry/exit` 把限流结构与业务编排混在 `upload()` 里（try/finally 嵌套两层），可读性差；
- entry/exit 成对纪律靠人工保证（参数不一致、异常路径漏 exit 都是隐患），注解由 SentinelResourceAspect 统一管理，业务抛异常也保证 exit；
- 用户明确要求改为注解方式。

## 改动清单

| 文件 | 改动 |
|---|---|
| `service/MediaUploadService.java` | `upload()` 删除手动 entry/exit；改 `@SentinelResource(value = SentinelRateLimitConfig.UPLOAD_RESOURCE, entryType = IN, blockHandler = "uploadBlocked")`；新增同类 `uploadBlocked(files, requestId, BlockException)` 转 `RATE_LIMITED`；资源名复用配置类常量（单一事实源，删本地副本） |
| `config/SentinelRateLimitConfig.java` | 删除 ParamFlowRule（用户级热点规则）及 `USER_ID_PARAM_INDEX`；保留 FlowRule×2（并发 + QPS）；Javadoc 更新分工说明 |
| `config/MediaProperties.java` | `RateLimit.perUser` 语义调整：Sentinel 热点参数阈值 → Redis UploadRateLimiter 阈值（字段与默认值不变） |
| `service/ratelimit/UploadRateLimiter.java` | Javadoc：接线位置描述更新（方法体内、validateBatch 之前）+ 阈值来源建议（`getPerUser()`） |
| `application.yml` | rate-limit 注释同步分工（thread/qps → Sentinel 注解；per-user → Redis） |
| `docs/modules/tf-gateway.md` | §3.1 重写为「机器维度 Sentinel + 用户维度 Redis」；结构树、配置表同步 |

## 设计要点

- **用户级限流为何退出 Sentinel**：`@SentinelResource` 的热点参数（ParamFlowRule）只能取方法签名参数 `(files, requestId)`，userId 在 ThreadLocal 中无法作为参数下标——除非把 userId 塞回方法签名（违背既有设计）。用户维度整体移交 Redis `UploadRateLimiter`（逻辑仍由使用者实现），跨实例统一计数本就优于单机热点规则；
- **`entryType = EntryType.IN` 必须显式**：注解默认 OUT（依赖被调方语义），Web 入口流量是 IN，漏配会污染链路统计口径；
- **blockHandler 签名契约**：与原方法同参 + 末尾 `BlockException`、同返回类型、同类 public 实例方法；不吞异常，转 `BizException(RATE_LIMITED)` 走全局异常处理器统一响应；
- **切面来源**：SCA starter（`spring-cloud-starter-alibaba-sentinel`）自动装配 `SentinelResourceAspect`，`spring.cloud.sentinel.annotation.enabled` 默认 true，无需额外配置类；
- **pom 零改动**：依赖与版本组合不变（Boot 3.4.3 / SC 2024.0.2 / SCA 2023.0.3.4）。

## 影响面

- 限流触发表现不变（`RATE_LIMITED(42901)` 标准错误结构），前端无感知；
- **行为差异**：原「每用户 10 QPS」单机热点规则不再生效（由 Redis 限流器实现后接管，跨实例口径）；机器维度并发/QPS 规则行为等价；
- `upload()` 方法体内限流时序：Sentinel（切面层，先）→ 业务校验（方法体内，后）；Redis 限流器接线后插在 `validateBatch` 之前。

## 验证

- 待本机 `mvn -pl tf-gateway -am compile`（沙箱无 Maven）；
- 运行时验证：将 `turbofeed.media.rate-limit.qps` 临时调成 1，快速连续上传两次应触发 RATE_LIMITED；启动日志仍含「Sentinel 上传限流规则已加载」；若注解不生效，检查 `spring.cloud.sentinel.annotation.enabled`（默认 true，显式 false 会关掉切面）。

## 回滚

`upload()` 还原为手动 `SphU.entry/exit` 结构 + `SentinelRateLimitConfig` 恢复 ParamFlowRule 注册 + 相关 Javadoc/yml/文档还原（对 git 单 revert 本提交即可）。
