# tf-shared 技术文档

> 公共契约库：`Result` / `ErrorCode` / `FeedItemView` / `FeedTimelineEvent`。
> 纯 POJO、**零第三方依赖**——被全部三个服务依赖，因此**禁止引入任何 Spring / 日志 / JSON 依赖**（会污染所有下游）。

## 0. 模块现状

| 包 | 内容 | 说明 |
|---|---|---|
| `com.turbofeed.shared.result` | `Result` / `ErrorCode` | ✅ 已实现，全部 HTTP 接口共用 |
| `com.turbofeed.shared.model` | `FeedItemView` / `FeedTimelineEvent` | ✅ 已实现，网关 ⇄ Feed 引擎的跨服务契约（B2 落地） |
| `com.turbofeed.shared.constants` | 空包（`.gitkeep`） | 预留 |
| `com.turbofeed.shared.spi` | 空包（`.gitkeep`） | 预留 |

**关键编译配置（不可删）**：本模块 `pom.xml` 单独为 `maven-compiler-plugin` 开启 `<parameters>true</parameters>`。

原因是 `FeedItemView` / `FeedTimelineEvent` 是 `record`，Jackson 反序列化走**规范构造器**、需要知道形参名；而根 pom 未开启 `-parameters` 时形参名会被编译成 `arg0/arg1`。在本模块单独开启，形参名随 `.class` 一起被下游读取，从而**既不需要 `@JsonProperty`（会引入 jackson-annotations、破坏"零依赖"定位），也不必把编译参数扩散到其他模块**。

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

> ⚠️ **已知非对称陷阱**：`isSuccess()` 会被 Jackson 当作 boolean getter 序列化出去，线上响应里因此多出一个 `"success": true` 字段；但**本类没有对应的反序列化路径**（无 setter、无 `@JsonCreator`），所以 `success` 是"写得出去、读不回来"的字段。当前 Boot 默认 `FAIL_ON_UNKNOWN_PROPERTIES=false`，因此能正常跑；一旦有人打开严格校验，**反序列化 `Result` 会整体失败**。
> 处置约定：**跨服务读取不要直接反序列化成 `Result<T>`**，用 `JsonNode` 取 `data` 字段（日志与检索务必与 `FeedItemView` 内部的同类规避保持一致）。对外已发布的 `Result` JSON 结构**不改**，避免破坏既有调用方。

## 2. 错误码 ErrorCode

分段：`0` 成功 / `4xxxx` 客户端侧 / `5xxxx` 服务端侧。

| 枚举 | code | 默认 message | 典型触发场景 |
|---|---|---|---|
| SUCCESS | 0 | 成功 | — |
| PARAM_ERROR | 40001 | 参数错误 | 参数校验失败 |
| UNAUTHORIZED | 40101 | 未登录或凭证已失效 | 令牌缺失/过期/签名不符 |
| ACCOUNT_NOT_REGISTERED | 40102 | 账号未注册，请先注册 | 登录时手机号查无记录（**演示 UX 优先，开放账号枚举**） |
| FORBIDDEN | 40301 | 无权限访问 | 已登录但缺少所需权限（RBAC 拦截） |
| NOT_FOUND | 40401 | 资源不存在 | — |
| RATE_LIMITED | 42901 | 请求过于频繁，请稍后重试 | Sentinel 限流 / 热点降级 |
| UPLOAD_INVALID | 42902 | 上传文件不合法 | 文件为空、类型不符、超大小限制 |
| UPLOAD_IN_PROGRESS | 42903 | 已有上传任务进行中，请稍后再试 | 并发护栏：同一用户已有上传在途 |
| SENSITIVE_WORD_HIT | 42904 | 内容包含敏感词 | AC 自动机扫描后 **fail-closed** 拒绝 |
| INTERNAL_ERROR | 50000 | 系统内部错误 | 未归类异常兜底 |
| DEPENDENCY_UNAVAILABLE | 50001 | 依赖服务暂不可用，请稍后重试 | Redis / MQ / MySQL 不可用 |

新增错误码规则：先查分段归属，客户端侧 `4xxxx`、服务端侧 `5xxxx`，编号在既有段内**追加不插队**。

## 3. 跨服务契约（B2 新增）

服务拆分后，Feed 时间线读模型的**读写双方分属两个部署单元**（引擎物化与读取、网关在审核/下架时投递变更），条目模型必须被双方同时引用。按 `service-split.md` §2「契约归 tf-shared」原则放在本模块，而不是各自复制一份。

### 3.1 FeedItemView —— 公域 Feed 条目

```java
record FeedItemView(
    String postId,        // 帖子标识 post/{userId}/{uuid}；历史数据为 null
    String mediaId,       // 帖子代表媒体（首图）标识；字段名保持历史语义
    String url,           // 首图地址（列表封面），与 images[0] 等值
    List<String> images,  // 帖内全部图片，按 seq 升序；历史数据为 null
    String status,        // 审核状态的字符串表示
    Instant createdAt,    // 上传时间（仅展示用，不参与分桶与排序）
    String caption,
    String captionMark)   // 描述解析后的结构化标记 JSON
```

**三个设计决策（均有明确理由）**：

1. **「一个成员 = 一个帖子」**。B2 之后公域时间线按**帖子**物化：同一次上传批次的 N 张图共用一条成员串（`images`），而不是一张图一条。因此两个回退方法是引擎幂等键与前端轮播的数据基础，**历史单图数据与新的多图帖子走同一段读取代码、无需分支**：
   - `timelineKey()` —— 时间线**幂等键与反查索引键**：优先 `postId`，历史数据回退 `mediaId`。**调用方必须统一经本方法取键、不要各自拼装**，否则新旧数据会落在两个不同的反查索引上，导致下架静默失效；
   - `imageUrls()` —— 保证非空且有序：`images` 缺省时回退为「单图帖」`[url]`。
2. **`status` 是 `String` 而不是枚举**。审核状态机（`MediaStatus`）是网关内部的业务概念，提升到契约层会让引擎被网关的状态机演进**绑死**。契约只承诺"状态的字符串表示"，网关侧负责双向映射，引擎侧只判断是否等于 `STATUS_APPROVED`。
3. **字段名与网关内部视图逐字相同**（`postId`/`mediaId`/`url`/`images`/`status`/`createdAt`/`caption`/`captionMark`），Jackson 默认序列化输出一致 → `tf:feed:tl:*` 中**已物化的历史成员串无需任何数据迁移**即可被本记录反序列化（历史串只有后 6 个字段，缺失的 `postId`/`images` 反序列化为 `null`，由上述回退方法统一为"单图帖"语义）。

### 3.2 FeedTimelineEvent —— 时间线变更事件

```java
record FeedTimelineEvent(
    String timelineKey,   // 帖身份（顺序消息 hashKey）
    String action,        // APPEND / REMOVE
    Integer poolLevel,    // 仅 APPEND 有值
    FeedItemView item,    // 仅 APPEND 有值
    Instant occurredAt)
```

**核心约束：`append` 与 `remove` 必须共用同一个 topic、同一个消费者，用 `action` 区分**。发送侧以 `timelineKey` 作顺序 hashKey（`syncSendOrderly`），保证同一帖的 append / remove 严格按序。**绝不能按 `action` 拆 tag / 拆消费者**——那会让同一帖落不同队列、失去顺序保证，出现「remove 先于 append 执行 → 已下架内容重新出现」的**内容安全事故**。

- `timelineKey` 取 `FeedItemView#timelineKey()`，与 `remove(String)` 的入参口径必须一致，这是「同一帖的 append/remove 落同一队列」的前提；
- 工厂方法：`append(item, poolLevel)` / `remove(timelineKey)`（精确摘除只需帖身份，`item` 为 null）。

### 3.3 零依赖下的命名纪律（踩过的坑）

`FeedItemView` / `FeedTimelineEvent` **刻意不提供** `isAppend()` / `isRemove()` / `getTimelineKey()` 这类 `is` / `get` 前缀方法。

实测：Jackson 会把 `isXxx()` 当作 boolean getter 序列化出去，线上消息体里因此多出 `"append":true,"remove":false`——既污染消息格式，又埋下「一旦开启严格反序列化整条消息解析失败」的隐患（与 §1 `Result.isSuccess()` **属同一类问题**）。本模块是零第三方依赖模块，**不能用 `@JsonIgnore` 规避**，因此从**命名上绕开**：调用方直接比较 `ACTION_APPEND` / `ACTION_REMOVE` 常量或调用无前缀方法即可（见引擎侧 `FeedTimelineConsumer`）。

> 新增契约字段时的纪律：任何方法名不要以 `get` / `is` 开头，除非你确实希望它成为一个序列化字段。

## 4. 演进规划

- `constants` / `spi` 两个空包为预留位（原规划的 `MqProvider` / `InboxStore` / `CounterStore` 等 SPI 已随实现路径调整，**不要在文档里当作已存在**）；
- 新增跨服务契约一律进本模块，并**同步覆盖本文件的字段表与理由**；
- 本模块任何新增依赖都需要评估对三个下游的污染面——默认答案是"不加"。
