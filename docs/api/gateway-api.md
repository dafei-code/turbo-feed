# tf-gateway HTTP 接口文档

> Base URL：`http://localhost:8080`（tf-gateway 服务）。
> 所有响应体为统一结构 `Result`（见 tf-shared 文档）：`{ code, message, data, timestamp }`，`code=0` 成功。

## 1. 认证约定

除登录本身外的受保护接口，需携带请求头：

```
Authorization: Bearer <JWT>
```

令牌经 `POST /api/auth/login` 获取（HS256，默认有效期 86400 秒）。令牌缺失时受保护接口返回 `40101 UNAUTHORIZED`；携带无效令牌在任何接口直接返回 `40101`（不静默降级匿名）。

## 2. 接口明细

### 2.1 登录换取 JWT

```
POST /api/auth/login          Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| username | form | 是 | 用户名（演示账号 admin） |
| password | form | 是 | 密码（演示密码 123456） |

响应 `data`：JWT 字符串。

```bash
curl -X POST 'http://localhost:8080/api/auth/login' \
     -d 'username=admin&password=123456'
```

```json
{ "code": 0, "message": "成功", "data": "eyJhbGciOi...", "timestamp": 1751217600000 }
```

错误：`40001`（参数为空）/ `40101`（账号或密码错误）。

### 2.2 内容图片上传（UGC，审核后展示）

```
POST /api/media/upload        Content-Type: multipart/form-data
```

参数：

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| Authorization: Bearer | header | 是 | 身份经 JWT 验签，userId 取自令牌（不接受参数传入） |
| files | form-data（可重复） | 是 | 图片文件数组，1–9 张，单张 ≤5MB |
| X-Request-Id | header | 否 | 客户端幂等键（幂等去重落地时使用） |

校验规则（后端强制，与前端约定一致）：

- 类型白名单 jpg / png / gif / webp，**按文件头 Magic Number 判定**（伪造扩展名拒绝），SVG 拒绝；
- 空文件、超限返回 `42902 UPLOAD_INVALID`。

响应 `data`：上传成功后的图片 URL 列表（审核通过后对前端生效；上传成功仅为受理进入 PENDING）。

```bash
TOKEN=$(curl -s -X POST 'http://localhost:8080/api/auth/login' \
        -d 'username=admin&password=123456' | sed -E 's/.*"data":"([^"]+)".*/\1/')

curl -X POST 'http://localhost:8080/api/media/upload' \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Request-Id: demo-req-001" \
     -F 'files=@/path/a.jpg' -F 'files=@/path/b.png'
```

```json
{
  "code": 0, "message": "成功",
  "data": ["https://oss.turbofeed.com/media/admin/uuid-1.jpg"],
  "timestamp": 1751217600000
}
```

错误：`40101`（未携带有效令牌）/ `42902`（文件为空、类型不符、超大小或张数）/ `42902`（框架层 multipart 超限）/ `50000`（读取失败等内部错误）。

### 2.3 健康检查（三服务通用）

```
GET {host}/actuator/health      → {"status":"UP"}
```

| 服务 | 地址 |
|---|---|
| tf-gateway | http://localhost:8080/actuator/health |
| tf-counter | http://localhost:8081/actuator/health |
| tf-feed-engine | http://localhost:8082/actuator/health |

## 3. 错误码总表

见 [tf-shared 文档 §2](../modules/tf-shared.md)。分段：0 成功 / 4xxxx 客户端 / 5xxxx 服务端。

## 4. 接口演进（规划，未实现）

| 接口 | 说明 |
|---|---|
| `GET /api/media/{mediaId}/status` | 客户端查询上传内容审核进度 |
| 审核裁决接口 | 后台审核员 APPROVED / REJECTED 操作（当前机审演示闭环在服务内自动完成） |
| Feed / 计数查询接口 | 随 tf-feed-engine / tf-counter 实现开放，gateway 聚合转发 |
