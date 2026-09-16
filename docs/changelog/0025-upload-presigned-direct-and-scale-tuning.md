# 0025 · 上传链路改为预签名直传，并落地规模优化项

> 日期：2026-09-16
> 范围：`tf-gateway`（代码 + 配置）、`turbo-feed-ui/user.html`（上传流程）
> 前置：`0022-multi-image-post.md`（一帖多图 / 整帖一审）
> 关联提交：`985ae80`

## 一、背景：原上传链路的三处结构性瓶颈

原链路为「客户端 → 网关（multipart）→ MinIO」，且逐张串行「处理 → 存储 → 落库」。压力测试口径下暴露三个问题：

1. **字节流全程经过网关**：上传是最重的网络 IO，却由计算节点转发，带宽与线程都被占住，
   网关成了上传吞吐的天花板。
2. **MinIO 客户端有隐藏并发上限**：`MinioClient.builder().build()` 使用默认 OkHttp，
   其 `Dispatcher.maxRequestsPerHost = 5` —— 所有指向同一 MinIO 主机的请求被串行化，
   与业务线程池、Sentinel 阈值都无关，是极易被忽略的硬卡点。
3. **逐张落库放大写压力**：N 张图 N 次 `INSERT`；同时连接默认 10/库（合计 20），
   Sentinel 上传阈值 `thread=20 / qps=100` 也偏低。

## 二、改动清单

### ① 预签名直传（核心，字节移出网关）

新增两个端点，与旧 `POST /api/media/upload` **并存**：

| 端点 | 状态码 | 职责 |
|---|---|---|
| `POST /api/media/presign` | 200 | 只接收**元数据**，签发每个对象的预签名 PUT URL |
| `POST /api/media/complete` | **202** | 复核已传对象 → 异步收尾 → 返回受理回执 |
| `POST /api/media/upload` | 200 | **保留**：`storage=local` 兜底与旧客户端兼容 |

配套新增：

- `UploadReservation` / `UploadReservationStore`：Redis 保存「已签发、未落库」的预约。
  完成阶段只认 `postId`，媒体清单一律取服务端预约——**不接受客户端声明 mediaId**，
  避免伪造对象名写入他人/非本批对象。三键分工：
  `presign:resv:{postId}`（预约体）、`presign:req:{uid}:{requestId}`（幂等索引）、
  `presign:claim:{postId}`（SETNX 领取收尾权，保证重复 complete 只落库一次）。
- `MediaUploadFinalizer`：**独立 Bean** 承载 `@Async("uploadFinalizeExecutor")` 收尾
  （可选重编码 → 整帖 `batchInsert` → 发一条帖级事件 → 回写幂等结果 → 失败补偿清理）。
  独立成类是刻意的：`@Async` 靠代理生效，若标在 `MediaUploadService` 自身方法上，
  同类内部调用会绕过代理、退化为同步执行。
- `MediaStorageClient` 增加 6 个**预签名扩展点**（`generateMediaId` / `presignedPutUrl` /
  `sizeOf` / `probeHeader` / `download` / `overwrite`），均为 `default` 方法且默认抛
  `UnsupportedOperationException`，`LocalDiskStorageClient` / `PlaceholderStorageClient` 零改动。

### ② 校验分两段：声明值 vs 真实内容

预签名下 `presign` 阶段**没有字节可读**，只能校验声明值（数量 / 声明大小 / contentType 白名单）。
因此真实性与合规性放在 `complete` 阶段由服务端读对象复核：

- `statObject` 复核真实大小（防超限 / 空对象）；
- 读对象头 12 字节做 Magic Number 检测，与声明格式比对（防"拿图片凭证传非图片"）。

**伪造 contentType 最多影响对象名后缀，过不了文件头复检**。该结论已由负向用例验证（见第四节）。

### ③ 孤儿对象清理（直传的固有成本）

直传把「字节落存储」与「元数据落库」拆成两步，客户端传完不通知完成（放弃 / 断网 / 崩溃）
就会留下**无任何 DB 行引用**的对象：不可见、用户也删不掉。

- 用 Redis ZSET `presign:pending` 登记待落库对象（member=mediaId，score=预约过期时刻）；
- 正常完成或已补偿清理会主动 `releasePending` 移除；
- `OrphanObjectCleanupTask` 定时扫描「已过期且未释放」的条目并删除对象（fail-open，
  失败重新入队延后重试）。判定口径保守：**宁可晚删，不删可能还在用的对象**。

### ④ 其他规模项

- `MinioStorageClient` 注入自定义 OkHttp：`maxRequestsPerHost` 5→256、连接池 256、专用 `minio-io` 线程池。
- `storeOne` 改为只存储不落库，主流程收尾一次 `batchInsert`（同 `user_id` 落同片，
  `rewriteBatchedStatements=true` 合并为单批）。
- 分片库 Hikari `maximumPoolSize` 10→50（每库）。
- Sentinel 上传阈值 `thread` 20→200、`qps` 100→2000。

### ⑤ 前端（`turbo-feed-ui/user.html`）

`submitSelection()` 由「FormData 直传网关」改为三段式：`presign` → 逐张 `PUT` 直传 → `complete`。
失败保留已选、沿用同一 `X-Request-Id` 重试，不会产生重复帖子。

## 三、MinIO 8.5.7 取证（javap 实测，非凭印象）

| 结论 | 影响 |
|---|---|
| `extraHeaders(Map)` 位于 `BaseArgs.Builder`（`GetPresignedObjectUrlArgs.Builder` 自身只有 `method` / `expiry`） | 用它把 `Content-Type` **纳入签名**，强制客户端 PUT 携带一致值；否则对象被存成 `octet-stream`，浏览器会下载而非渲染 |
| `ObjectReadArgs.Builder` **只有 `ssec`，无 `offset` / `length`** | 该版本**无 SDK 级分段读**；头部探测改为「开流读 12 字节即关」（OkHttp 只取已消费缓冲）。12 字节是为覆盖 WEBP（标识位于偏移 8、长 4） |

## 四、验证

端到端（Redis / MinIO / MySQL 全部真实运行）：

1. **正向**：`presign`(200) → 直传 `PUT`(200) → `complete`(**202**) → 异步收尾后
   `/status` 由 `PENDING` 转 `APPROVED` → `/api/media/mine` 可见该帖 2 张图。
2. **负向（安全）**：声明 `image/png` 却实传 JPEG 字节 → `complete` 拒绝并返回
   `42902 文件内容与声明类型不符`，对象被立即清理。
3. **孤儿回收**：制造"传完不 complete"的对象并强制到期 → 清理任务 4 秒内回收（对象 404、名单条目移除）；
   同时验证**正常完成的对象不会被误删**（仍在 200、且已从名单释放）。
4. **浏览器直传前提**：对 MinIO 发起 `OPTIONS` 预检，返回
   `Access-Control-Allow-Methods: PUT` 与 `Allow-Headers: content-type`，跨域直传无需额外配置。

测试数据已全部清理（DB 行硬删至 0、MinIO 对象 404、Redis 键无残留）。

## 五、已知限制与后续

1. **预签名仅在 `storage=minio` 可用**：本地磁盘实现无预签名能力，`presign` 会直接返回业务错误
   （而非 500）。旧 `/api/media/upload` 继续承担本地模式。
2. **桶仍为匿名可读**：读取侧签名（`presignedGetUrl`）未实现，生产应关闭 `public-read` 并按内容状态签发读凭证。
3. **未做分片/断点续传**：当前仅图片（≤5MB×9），整文件重传成本可接受。若放开大图或视频，
   演进为 S3 MultipartUpload + 服务端 `ListParts` 恢复进度。
4. **完成通知由客户端触发**：更彻底的形态是对象存储事件 → MQ → 消费端收尾（抖音同款），
   需配置 MinIO bucket notification，本次未做。
