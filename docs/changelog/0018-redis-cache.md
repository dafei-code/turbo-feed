# 0018 读路径接入 Redis 旁路缓存（P2）

## 背景
P1 / P1.5 已把 media 落库并按 `user_id` 分片、查询分页化、公域 feed 与个人中心接口解耦。
对标抖音级「读多写少」场景，热点读（公域推荐流、个人中心状态轮询）直接打 DB 不可持续，
需在读路径加缓存，压降 DB 读压力。

## 方案
- 缓存选型：复用已引入的 `spring-boot-starter-data-redis`（Lettuce），`StringRedisTemplate` 级 KV，
  **零新增依赖**（与 `UploadRateLimiter` / `ConcurrentUploadValidator` 同款注入方式）。
- 旁路缓存（Cache-Aside）+ fail-open 降级：
  - 公域推荐流 `GET /api/feed/recommended`：key = `tf:feed:rec:{page}:{size}`，TTL = 15s；
    命中直接返回，未命中回源 `listApprovedGlobal` 并回填。
  - 单条状态查询：key = `tf:media:status:{mediaId}`，TTL = 60s；
    命中 `MediaStatus.valueOf`，未命中回源 `getStatus`（null → PENDING）并回填。
  - 任何缓存异常（连接 / 序列化）均 `catch` 后回源 DB，绝不阻断读路径。
- 一致性：写入侧 `MediaReviewService.review` 在 `updateStatus` 后精确失效
  `tf:media:status:{mediaId}`；公域推荐流不主动批量失效，依赖 15s TTL 自然过期（最终一致）。

## 序列化约定
- 状态缓存直接存枚举 `name()` 字符串（非 JSON），反序列化用 `MediaStatus.valueOf`，无 Jackson 依赖，最简单可靠。
- 推荐流缓存存 `List<MediaItem>` JSON。因项目编译未开启 `-parameters`，Jackson 在无参数名信息时
  无法反序列化 record 的 canonical 构造器——故给 `MediaItem` record 各组件显式加 `@JsonProperty`，
  保证 JSON 能稳定往返，且与 IDEA 直接 run 的编译设置无关（比改 pom 加 `-parameters` 更可靠）。

## 取舍与边界
- **为什么不清推荐流**：主动批量清需 Redis `keys` / `SCAN`，单实例虽可用但生产有阻塞风险；
  15s 短 TTL 的最终一致对公域推荐流可接受（推荐流本就是近似实时，非强一致）。
- 推荐流仍是跨分片广播反模式占位（`listApprovedGlobal`），生产由推荐服务 + 异构索引取代（见 0017）。
- Redis 未启动时不报错：Lettuce 懒连接，异常被 fail-open 捕获回源，应用照常可用。

## 验证
- 本地启动 Redis（Docker）后：连续两次请求推荐流，第二次走缓存（DB 查询仅一次）；
  审核通过后个人中心状态立即刷新（缓存失效生效）。
- 不启动 Redis：读路径全部回源 DB，功能不受影响（fail-open）。
- 编译：`MediaItem` 加 `@JsonProperty` 后 record JSON 往返正常；IDEA / `mvn` 均无需 `-parameters`。
