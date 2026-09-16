# tf-gateway HTTP 接口文档

> Base URL：`http://localhost:8080`（tf-gateway 服务）。
> 所有响应体为统一结构 `Result`（见 tf-shared 文档）：`{ code, message, data, timestamp }`，`code=0` 成功。
> 后台类接口按 **RBAC 权限**校验（`@RequirePermission`），已登录但权限不足返回 `40301`。

## 1. 认证与权限约定

除登录 / 注册 / 公域推荐流 / 评论列表外，受保护接口需携带请求头：

```
Authorization: Bearer <JWT>
```

令牌经 `POST /api/auth/login` 获取（HS256，默认有效期 86400 秒）。令牌缺失时受保护接口返回 `40101 UNAUTHORIZED`；携带无效令牌在任何接口直接返回 `40101`（不静默降级匿名）。

令牌载荷携带 `sub`（userId）与 `role`。角色由 **`user` 表 `role` 列**派生，**不在配置里维护任何手机号白名单**。角色 → 权限映射：

| 角色 | 权限 |
|---|---|
| `USER`（默认） | 无后台权限 |
| `REVIEWER` | `CONTENT_REVIEW` / `CONTENT_TAKEDOWN` / `DASHBOARD_VIEW` |
| `ADMIN` | 全部（另含 `USER_MANAGE` / `SYSTEM_CONFIG` / `CREDIT_MANAGE`） |

未知角色编码一律降级为 `USER`（最小权限原则）。接口按**权限**校验而非按角色名，新增角色只需加枚举值 + 一行权限映射。

> **身份来源**：`userId` 由 `JwtAuthenticationFilter` 验签后绑定 `ThreadLocal`，业务层经 `UserContextHolder.requireUserId()` 获取。**任何接口都不接受客户端传入 userId**（防越权）。

## 2. 接口明细

### 2.1 注册

```
POST /api/auth/register        Content-Type: application/x-www-form-urlencoded
```

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| phone | form | 是 | 登录手机号 |
| password | form | 是 | 密码 |
| nickname | form | 否 | 昵称，默认空串 |

### 2.2 登录换取 JWT

```
POST /api/auth/login           Content-Type: application/x-www-form-urlencoded
```

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| phone | form | 是 | 登录手机号（**按手机号登录**，不是用户名） |
| password | form | 是 | 密码 |

响应 `data`：JWT 字符串。

演示账号（种子数据见 `deploy/mysql/init-local.sql`）：

| 手机号 | 密码 | 角色 | 前端入口 |
|---|---|---|---|
| 13800138000 | 123456 | ADMIN | admin.html |
| 13700137000 | 123456 | REVIEWER | review.html |
| 13900139000 | 123456 | USER | user.html |

```bash
curl -X POST 'http://localhost:8080/api/auth/login' \
     -d 'phone=13800138000&password=123456'
```

```json
{ "code": 0, "message": "成功", "data": "eyJhbGciOi...", "timestamp": 1751217600000 }
```

错误：`40001`（参数为空）/ `40101`（密码错误）/ `40102`（该手机号未注册）。

### 2.3 内容图片上传（UGC，一帖多图，审核后展示）

```
POST /api/media/upload         Content-Type: multipart/form-data
```

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| Authorization: Bearer | header | 是 | 身份经 JWT 验签，userId 取自令牌（不接受参数传入） |
| files | form-data（可重复） | 是 | 图片文件，**1–9 张**，单张 ≤5MB。**一次请求 = 一个帖子** |
| caption | form-data | 否 | 抖音式描述（`@用户` / `#话题` / `[image:idx:filename]`，**整帖共享**），需过敏感词校验 |
| X-Request-Id | header | 否 | 客户端幂等键 |

校验规则（后端强制，与前端约定一致）：

- 类型白名单 jpg / png / gif / webp，**按文件头 Magic Number 判定**（伪造扩展名拒绝），SVG 拒绝；
- 空文件 / 类型不符 / 超大小 / 超张数 → `42902 UPLOAD_INVALID`（传 10 张报「单次最多上传 9 张」）；
- 同用户上传并发护栏 → `42903 UPLOAD_IN_PROGRESS`；
- caption 命中敏感词 → `42904 SENSITIVE_WORD_HIT`。

响应 `data`：**帖子视图**（`MediaItem`），不是 URL 字符串数组。

```bash
TOKEN=$(curl -s -X POST 'http://localhost:8080/api/auth/login' \
        -d 'phone=13800138000&password=123456' | sed -E 's/.*"data":"([^"]+)".*/\1/')

curl -X POST 'http://localhost:8080/api/media/upload' \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Request-Id: demo-req-001" \
     -F 'caption=第一张 #风景' \
     -F 'files=@/path/a.jpg' -F 'files=@/path/b.png'
```

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "postId": "post/1000000000000000001/bdf172238df24f00b2179e5104f46b9d",
    "mediaId": "media/1000000000000000001/0879cc03-9a83-4a58-b869-c649f3b0dccf.png",
    "url": "http://127.0.0.1:9000/turbo-feed-media/media/1000000000000000001/0879cc03-....png",
    "images": [
      "http://127.0.0.1:9000/turbo-feed-media/media/.../0879cc03-....png",
      "http://127.0.0.1:9000/turbo-feed-media/media/.../1a2b3c4d-....png"
    ],
    "seq": 0,
    "status": "PENDING",
    "createdAt": "2026-09-14T11:49:20Z",
    "caption": "第一张 #风景",
    "captionMark": "{\"hashtags\":[\"风景\"]}"
  },
  "timestamp": 1751217600000
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `postId` | 帖子标识（`post/{userId}/{uuid}`），**一次上传批次一个**；历史单图数据为 `null` |
| `mediaId` | 帖子**代表媒体**标识（首图）。帖级操作（审核 / 删除 / 举报 / 申诉 / 状态查询）统一传它 |
| `url` | 首图可访问地址（封面），与 `images[0]` 等值 |
| `images` | 帖内全部图片 URL，按 `seq` 升序（前端轮播顺序）。**保证非空**（历史单图数据回退为单元素列表），前端无需判空 |
| `seq` | 帖内序号（帖子视图恒为 0；行级视图为真实序号） |
| `status` | 审核状态，取值见 §3 |
| `createdAt` | 上传时间（ISO-8601 字符串） |
| `captionMark` | 描述解析后的结构化标记 JSON（@用户 / #话题 / 图片引用），服务端生成、前端只读 |

> **上传成功 ≠ 前端可见**：本接口返回 `PENDING`（受理态），须经审核流转为 `APPROVED` 后才进入公域。

### 2.4 我的内容（按帖聚合）

```
GET /api/media/mine?page=0&size=50&status=APPROVED
```

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| page | query | 否 | 页码，从 0 开始，默认 0 |
| size | query | 否 | 单页**帖子**数，默认 50（≤0 兜底 50） |
| status | query | 否 | 按状态过滤；**不传时自动过滤掉 `DELETED`**，避免已删内容重现 |

响应 `data`：`MediaItem` 数组。**一条帖子只占一行**（一帖 9 图不会散成 9 条），每行回带整帖 `images`。

> 分页发生在「帖代表行」上，因此一帖只占一个分页位，不会出现「9 张图吃掉 9 个位置」。

### 2.5 查询单条内容状态

```
GET /api/media/status?mediaId=<mediaId>
```

响应 `data`：状态枚举值。内容已受理但审核事件未到时返回 `PENDING`（处理中）。

### 2.6 更新描述 / 标题

```
POST /api/media/caption?mediaId=<mediaId>&caption=<新描述>
```

更新**整帖**的描述（描述是帖级属性，一次上传共用一个）；走与上传一致的敏感词校验（命中返回 `42904`）；**不影响审核状态**（改文案 ≠ 内容违规降级）。

### 2.7 删除内容

```
DELETE /api/media?mediaId=<mediaId>
```

语义：对象存储（MinIO / 本地磁盘）**物理删除** + MySQL **逻辑删除**（整帖置 `DELETED`）。
身份（JWT）与 `user_id` 分片键双重约束，不能删除他人内容。

> **公域下架为最终一致**：引擎推荐流存在 15s 旁路缓存，删除后最多 15s 内公域仍可能返回该帖。

### 2.8 公域推荐流（免登录）

```
GET /api/feed/recommended?page=0&size=20
```

响应 `data`：`MediaItem` 数组（帖子视图，含 `images`），供前端渲染**整帖轮播**。

只返回 `APPROVED` 内容。数据由 **tf-feed-engine** 的时间线读模型提供（网关转发、不持有读模型）；引擎不可用时按 `turbofeed.feed.degraded-mode` 降级——默认 `empty`（返回空列表 + WARN，**拒绝跨分片广播**）。

### 2.9 举报（任意登录用户）/ 申诉（作者）

```
POST /api/media/report?mediaId=<mediaId>&reason=<理由>
POST /api/media/appeal?mediaId=<mediaId>
```

| 接口 | 前置状态 | 行为 |
|---|---|---|
| 举报 | 仅 `APPROVED`（否则 `40001`） | 理由含**高危词**（涉政 / 暴恐 / 儿童 / 未成年）时**立即整帖下架停推**（`TAKEN_DOWN`）并扣作者信用；普通举报入人工队列 |
| 申诉 | 仅 `REJECTED` / `TAKEN_DOWN` | 整帖转 `APPEALING`（暂不可见）并进人工复核 |

> 举报 / 申诉**作用于整帖**：帖内任一图被举报即整帖不可见，避免「举报 9 张里的 1 张，其余 8 张继续可见」的绕过路径。

### 2.10 评论

```
POST /api/comments?mediaId=<mediaId>&content=<内容>&parentId=0   # 发表（parentId=0 为一级评论，>0 为楼中楼回复）
GET  /api/comments?mediaId=<mediaId>&rootId=0&page=0&size=20      # 列表（rootId>0 取该根评论下的回复）
POST /api/comments/like?mediaId=<mediaId>&commentId=<id>          # 点赞，返回 Boolean
```

发布 / 点赞**必须登录**；列表为公开读。评论按 `media_id` 独立分片（与 `media` 表按 `user_id` 分片正交），因此「读某条内容下的全部评论」单分片命中。

### 2.11 管理端：内容审核

权限：`CONTENT_REVIEW`（`pending` / `review` / `appeal-review`）、`CONTENT_TAKEDOWN`（`report-review`）。

```
GET  /api/admin/media/pending?page=0&size=20
POST /api/admin/media/review?mediaId=<mediaId>&approve=true|false
POST /api/admin/media/report-review?mediaId=<mediaId>&confirmed=true|false
POST /api/admin/media/appeal-review?mediaId=<mediaId>&upheld=true|false
```

- `mediaId` 传**帖代表行**；审核动作**作用于整帖**（机审一次、状态一次翻转、公域时间线一次投递），审核队列里的每个帖子会铺开其全部图片供审核员判断；
- `review` 返回流转后的状态；若帖子已是终态、或已被另一条路径（如异步先发后审）流转，**幂等返回当前状态且不重复投递时间线**；
- 并发安全：状态流转走 CAS（`UPDATE ... AND status = 期望前置态`）+ 有界重试，避免整帖多行 UPDATE 的 InnoDB 死锁（见 [changelog 0023](../changelog/0023-post-review-deadlock-cas.md)）。

### 2.12 管理端：敏感词库（权限 `SYSTEM_CONFIG`）

```
GET  /api/admin/moderation/words?page=0&size=50
POST /api/admin/moderation/words?word=<词>&category=DEFAULT
POST /api/admin/moderation/words/disable?id=<id>
POST /api/admin/moderation/words/enable?id=<id>
POST /api/admin/moderation/words/delete?id=<id>
POST /api/admin/moderation/words/reload      # 热加载词库，返回加载条数
```

### 2.13 健康检查（三服务通用）

```
GET {host}/actuator/health      → {"status":"UP"}
```

| 服务 | 地址 |
|---|---|
| tf-gateway | http://localhost:8080/actuator/health |
| tf-counter | http://localhost:8081/actuator/health |
| tf-feed-engine | http://localhost:8083/actuator/health |

## 3. 审核状态（MediaStatus）

| 值 | 含义 | 公域可见 |
|---|---|---|
| `PENDING` | 已受理、待审核（上传即进入） | 否 |
| `APPROVED` | 审核通过 | **是** |
| `REJECTED` | 审核驳回（**发布前**就被拦下） | 否 |
| `DELETED` | 用户主动删除（逻辑删除） | 否 |
| `TAKEN_DOWN` | 发布后被举报 / 复审确认违规，**下架停推**（区别于 `REJECTED`：它是发布后才被拿下的） | 否 |
| `APPEALING` | 作者申诉处理中（内容暂不可见） | 否 |

状态持久化在 **MySQL `media` 表的 `status` 列**（非内存态，重启不丢）；整帖各行状态一致。

## 4. 错误码总表

分段：`0` 成功 / `4xxxx` 客户端侧 / `5xxxx` 服务端侧。详见 [tf-shared 文档](../modules/tf-shared.md)。

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 40001 | 参数错误 |
| 40101 | 未登录或凭证已失效 |
| 40102 | 账号未注册，请先注册 |
| 40301 | 无权限访问（RBAC 校验失败） |
| 40401 | 资源不存在 |
| 42901 | 请求过于频繁，请稍后重试（Sentinel 限流） |
| 42902 | 上传文件不合法（空 / 类型 / 大小 / 张数） |
| 42903 | 已有上传任务进行中，请稍后再试 |
| 42904 | 内容包含敏感词 |
| 50000 | 系统内部错误 |
| 50001 | 依赖服务暂不可用，请稍后重试（Redis / MQ / MySQL） |

## 5. 接口演进（规划，未实现）

| 接口 | 说明 |
|---|---|
| 计数查询接口 | 随 tf-counter 实现开放，gateway 聚合转发 |
| 断点续传 / 大文件 | 当前仅图片（≤5MB）整文件重传，不做断点续传；放开视频时走 S3 MultipartUpload + 客户端 checkpoint |
