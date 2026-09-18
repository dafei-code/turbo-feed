# 0031 管理端点鉴权兜底改为 deny-by-default（P0-2）

- 日期：2026-09-17
- 范围：`tf-gateway`（`PermissionInterceptor` / `WebConfig` / `RequirePermission`）
- 关联：[内容安全后续方案](../../turbo-feed-内容安全后续方案.html) 阶段一 · P0-2

## 1. 动机

`PermissionInterceptor.preHandle` 对未标注 `@RequirePermission` 的 `/api/admin/**` 端点
兜底要求 `Permission.CONTENT_REVIEW`。`CONTENT_REVIEW` 是**最低**管理权限，审核员(REVIEWER)
即持有——于是「漏标注解的 admin 端点」可被审核员进入，构成可利用的越权面
（例如本应仅 ADMIN 的管理操作被 REVIEWER 触碰）。

> `WebConfig` 已明确不再注册 `AdminAuthInterceptor`（其仅放行 ADMIN 会挡住审核员），
> 鉴权统一收口到 `PermissionInterceptor`，故该兜底确为全局生效的真实漏洞，而非死代码分支。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `PermissionInterceptor.java` | 兜底从 `new Permission[]{CONTENT_REVIEW}` 改为：未声明 `@RequirePermission` 即抛 `BizException(FORBIDDEN, "管理端点未声明 @RequirePermission")`（deny-by-default）。新增 `BizException`/`ErrorCode` import |
| `WebConfig.java` | 拦截器注册注释同步：明确「未声明注解一律拒绝」 |
| `RequirePermission.java` | javadoc 同步：缺失注解「一律拒绝（deny-by-default）」，不再暗示默认 CONTENT_REVIEW |

## 3. 关键决策

- **强制显式声明**：每个 `/api/admin/**` 端点必须 `@RequirePermission(...)` 才放行，
  新增端点忘写注解会在测试/启动期即被发现（而非悄悄对 REVIEWER 开放）。
- **不误伤现有端点**：全局确认仅两个 admin 控制器
 （`AdminMediaController` 4 端点、`AdminSensitiveWordController` 6 端点）共 10 个端点
  **全部**带 `@RequirePermission` 注解，改 deny 不影响任何已声明端点。

## 4. 验证

- 全仓检索 `/api/admin` 仅命中上述两个控制器，且全部端点已声明权限；`@RequirePermission`
  为 `@Target(METHOD)` 单级注解，无类级兜底可绕。
- 编译：`mvn -pl tf-gateway -am compile` 通过（exit 0）。
- 建议（未做）：单测对「无注解的伪 admin 端点」断言返回 40301。

## 5. 遗留

`AdminAuthInterceptor` 为已停用旧类（未被注册），保留仅作历史参考，未删除（非本次范围）。
