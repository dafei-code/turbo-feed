# 0008 - 上传校验重构为责任链（修复原实现编译与功能缺陷）

## 变更类型

`refactor(gateway)`：把散落的原 `handler/` + `validate/` 两组校验代码重构为 `service/validation` 责任链（模板方法防断链 + 原子并发护栏）。

## 动机

用户补充的责任链实现存在编译级 bug 与功能性缺陷，命名与分层也不规范：

- **编译失败**：`ValidateChain.execute` 调用 `handlers.get(0).execute(context)`——`ValidateHandler` 根本没有 `execute` 方法；
- **功能失效**：`RedisValidate` 只 `hasKey` 从不 `SET`，key 永不存在导致防并发形同虚设；且「先 hasKey 再判断」存在 TOCTOU 竞态（两并发请求同时通过），需改为 `SET NX EX` 原子占位；
- **链断裂**：所有环节都没调 `next.validate()`，链仅靠 `@Order` 排序却无推进机制，实际上只有首环可能执行；
- **重复校验**：`FileValidate` 丢掉了 Magic Number 嗅探（仍残留在 `MediaUploadService.detectFormat`），且空文件/大小校验与 Service 里的 `validate()` 执行两遍；
- **错误处理**：`RedisValidate` 抛裸 `RuntimeException` 绕过 `BizException` 全局口径；本地无 Redis 时 `hasKey` 直接连异常，破坏「克隆即跑」；
- **命名/分层**：`ValidateVo` 实为上下文非 VO；`FileValidate`/`RedisValidate` 是「动词+技术手段」命名（应为意图命名）；`handler` 与 `validate` 两包职责纠缠。

## 改动清单

| 文件 | 改动 |
|---|---|
| `service/validation/UploadValidation.java` | 新增：校验上下文（Pipeline Context）。携带 userId/files + `recordFormat(i, fmt)` 格式写回 + `onCompletion(Runnable)` 完成回调注册 + `runCompletionCallbacks()` 隔离执行 |
| `service/validation/UploadValidator.java` | 新增：抽象基类，`validate` 为 final 模板方法（doValidate → next），子类只能实现 `doValidate`，链推进不可遗漏 |
| `service/validation/UploadValidationChain.java` | 新增：`@Component`，注入 `List<UploadValidator>` 按 `@Order` 排序，构造期一次性 setNext 建链；`validate(userId, files)` 入口返回上下文 |
| `service/validation/ConcurrentUploadValidator.java` | 新增：环1(Order1)。`SET key 1 NX EX ttl` 原子占位（`rl:upload:inflight:{userId}`）；占位成功注册删除回调；Redis 连接失败 fail-open 放行；触发抛 `UPLOAD_IN_PROGRESS` |
| `service/validation/FileConstraintValidator.java` | 新增：环2(Order2)。批量数 → 空文件 → 大小 → Magic Number 流式嗅探（白名单 jpg/png/gif/webp，排除 SVG），格式写回上下文；触发抛 `UPLOAD_INVALID` |
| `service/MediaUploadService.java` | 重写：`upload()` 接 `validationChain.validate()`，主链路取 `context.format(i)` 不再重复嗅探；`finally` 统一 `context.runCompletionCallbacks()` 释放并发占位；删除旧 `validate/validateBatch/detectFormat` 及坏 import（`lombok.val`） |
| `shared/result/ErrorCode.java` | 新增 `UPLOAD_IN_PROGRESS(42903, "已有上传任务进行中，请稍后再试")` |
| `config/MediaProperties.java` | `RateLimit` 新增 `inflightTtlSeconds`（默认 30，getter/setter） |
| `application.yml` | `rate-limit` 下新增 `inflight-ttl-seconds: 30` 及注释 |
| `validate/`、`handler/` 两包（5 文件） | 删除：`ValidateChain / FileValidate / RedisValidate / ValidateHandler / ValidateVo` |
| `docs/modules/tf-gateway.md` | §1 结构树新增 `validation/`；§3 主链路改写；新增 §3.2 校验责任链；§6 配置表补 `inflight-ttl-seconds` |

## 设计要点

- **模板方法防断链**：基类 `validate` final，子类无法忘记调 next（手工责任链最常见断链事故根除）；
- **TOCTOU 根治**：`SET NX EX` 单命令原子完成「检查+占位」，并发两请求只会有一个拿到 NX 占位，彻底消除原 `hasKey` 竞态；
- **三重释放保障**：① 正常释放（占位方注册删除回调，finally 触发）② TTL 兜底（进程崩溃/删除失败自动过期，用户不会永久锁死）③ 占位者独占删除权（误删他人占位窗口消除）；
- **fail-open 决策**：Redis 不可用时放行而非拒绝——并发护栏是防护增强非正确性依赖，可用性优先；升级强护栏应改 fail-closed 抛 `DEPENDENCY_UNAVAILABLE`；
- **校验外移、主链路零改**：批量/空/大小/Magic Number/并发护栏全在链内；新增规则 = 加一个 `UploadValidator` 实现 + `@Component @Order(n)`，`MediaUploadService` 零改动；
- **单校验失败即异常**：不做布尔回传，主链路零分支，统一冒泡到 `GlobalExceptionHandler`。

## 影响面

- 校验触发表现不变：非法文件仍返回 `UPLOAD_INVALID(42902)`，并发中返回 `UPLOAD_IN_PROGRESS(42903)`（新增码），前端对 429xx 段已统一处理；
- **新增并发护栏**：同一用户同一时刻仅允许一个上传进行中（原实现此能力失效，本次真正生效）；
- **去重**：原 `MediaUploadService.detectFormat` 与 `validate()` 的重复校验消除，Magic Number 仅由 `FileConstraintValidator` 嗅探一次并写回上下文；
- 行为等价：正常上传返回 URL 列表、进入 PENDING 的逻辑未变。

## 验证

- 待本机 `mvn -pl tf-gateway -am compile`（沙箱无 Maven）；
- 编译前自查：`validate/handler` 两包已删且无残留 import；`UploadValidation` 构造器包内可见（同包 `UploadValidationChain` 可调用）；`ConcurrentUploadValidator` 仅消费 `MediaProperties.getRateLimit().getInflightTtlSeconds()`（已存在）；
- 运行时验证：同一用户并发发两次上传（或连点），第二次应返回 42903；本地无 Redis 时首次上传应正常（fail-open 放宽日志），不报 500；
- 限流分工不变：机器维度仍走 Sentinel 注解（thread/qps），用户维度仍由 `UploadRateLimiter.tryAcquire` 接管（`per-user` 阈值）。

## 回滚

删除 `service/validation/` 包 + 恢复 `validate/handler` 两包原始实现 + 还原 `MediaUploadService` / `ErrorCode` / `MediaProperties` / `application.yml` / 模块文档（git 单 revert 本提交即可）。
