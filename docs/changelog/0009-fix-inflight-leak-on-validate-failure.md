# 0009 - 修复上传并发占位 key 在校验失败时泄漏（0008 hotfix）

## 变更类型

`fix(gateway)`：单点 hotfix，行为修正（占位释放时机从「业务完成后」改为「进入方法即登记、无论成败立即释放」）。

## 动机

0008 重构后 `MediaUploadService.upload()` 的 `try/finally` 范围**未覆盖 `validationChain.validate()`**——当 `FileConstraintValidator` 抛 `UPLOAD_INVALID`（文件超大/格式错/批量超限）时：

1. `ConcurrentUploadValidator` 已成功 `SET NX EX` 占位 `rl:upload:inflight:{userId}`
2. `FileConstraintValidator` 抛 `BizException`，异常从 `validate()` 冒泡出 `try/finally`
3. `runCompletionCallbacks()` 不执行
4. 占位 key 滞留 Redis，只能等 30s TTL 过期才释放
5. **用户在 30s 内重试上传会直接被 42903 拒绝**，即便前一次是校验失败

用户体感：第一次传错图，立刻改对了再传，提示「已有上传任务进行中」——非常迷惑，且无法靠重试绕过（只能等 TTL）。

Javadoc 此前已写「无论成功、校验失败还是业务异常，占位都被释放」，但实现并未真覆盖校验失败——文档与代码不一致。

## 改动清单

| 文件 | 改动 |
|---|---|
| `service/MediaUploadService.java` | `upload()`：`validate()` 移入 `try` 块；`context` 声明为 `null` 占位，try 内赋值；`finally` 加 `context != null` 防御空链边界；Javadoc 明确 try/finally 覆盖范围并指向本 changelog |

## 设计要点

- **try/finally 覆盖到资源获取点**：凡涉及「先获取外部资源、失败时抛业务异常」的模式，资源释放的 finally 必须包含资源获取的整个调用链，否则抛异常路径泄漏。本场景中 `validationChain.validate()` 既获取了占位 key 又可能在后续环节抛异常，必须整体纳入 try；
- **context != null 防御**：理论上 `UploadValidationChain.validate()` 即便 validators 列表为空（无任何 `@Component` 实现）也会返回空 context，try 内若 `validate()` 抛 NPE 不会到这里（构造期 list 不空是基本不变量），但加 null check 成本为零、防御性更稳；
- **TTL 仍保留**：进程崩溃 / Redis 释放失败的兜底机制不变，本 hotfix 不调整 `inflight-ttl-seconds`。

## 影响面

- 行为修正：同用户校验失败后**立即**可重试，无需等 30s TTL；
- 占位释放时机由「业务完成后」提前到「任何路径退出 try 块」；
- `storeOne` 异常、JVM 关闭等已有路径行为不变（已被原 try/finally 覆盖）；
- Sentinel 注解切面（`@SentinelResource`）外层仍保证 entry/exit 成对，与本 try/finally 正交。

## 验证

- 待本机 `mvn -pl tf-gateway -am compile`（沙箱无 Maven）；
- 运行时验证：同一用户先上传一张超大图（应返 42902 UPLOAD_INVALID），**立刻**重新上传合规图——修复前会返 42903，修复后应直接受理；
- 控制台日志应见：`"上传占位释放失败（依赖 TTL 兜底过期）"` 不会因校验失败路径而出现（占位 key 已被正常 delete）。

## 回滚

`upload()` 还原为 `UploadValidation context = validationChain.validate(...)` 在 try 之外的原结构（会重新引入 30s 误拒窗口）。
