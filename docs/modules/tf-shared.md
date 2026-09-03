# tf-shared 技术文档

> 公共契约库：`Result` / `ErrorCode`。纯 POJO、零框架依赖——被全部三个服务依赖，
> 因此**禁止引入任何 Spring / 日志 / JSON 依赖**（会污染所有下游）。

## 1. 统一返回结构 Result

所有 HTTP 接口响应体：

```json
{
  "code": 0,
  "message": "成功",
  "data": { },
  "timestamp": 1751217600000
}
```

约定：`code = 0` 成功；非 0 失败。工厂方法 `ok()` / `ok(data)` / `fail(ErrorCode)` / `fail(ErrorCode, message)` / `fail(code, message)`。

## 2. 错误码 ErrorCode

分段：`0` 成功 / `4xxxx` 客户端侧 / `5xxxx` 服务端侧。

| 枚举 | code | 默认 message |
|---|---|---|
| SUCCESS | 0 | 成功 |
| PARAM_ERROR | 40001 | 参数错误 |
| UNAUTHORIZED | 40101 | 未登录或凭证已失效 |
| FORBIDDEN | 40301 | 无权限访问 |
| NOT_FOUND | 40401 | 资源不存在 |
| RATE_LIMITED | 42901 | 请求过于频繁，请稍后重试 |
| UPLOAD_INVALID | 42902 | 上传文件不合法 |
| INTERNAL_ERROR | 50000 | 系统内部错误 |
| DEPENDENCY_UNAVAILABLE | 50001 | 依赖服务暂不可用，请稍后重试 |

新增错误码规则：先查分段归属，客户端侧 4xxxx、服务端侧 5xxxx，编号在既有段内追加不插队。

## 3. 演进规划

Feed 契约模型（`User` / `FeedItem` / `LikeEvent` / SPI `MqProvider` / `InboxStore` / `CounterStore`）按引擎模块实现进度迁入本模块——契约先行，实现后置，是引擎间解耦的关键边界。
