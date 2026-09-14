# 0021 · B2 Feed 时间线投递改 RocketMQ 顺序消息，消除静默丢失窗口

> 日期：2026-09-14
> 范围：只改「投递侧」——把 B1 的「同步 HTTP + fail-open」换成可靠消息投递。读侧不变。
> 提交：`e409124`

## 一、背景：B1 遗留的静默丢失

B1 把时间线读模型搬到了引擎，但写侧仍是网关**同步调用**引擎 HTTP 接口，且 **fail-open**：

- 引擎抖动 / 重启 / 网络闪断 → 投递失败被吞掉，**内容静默不入流**；
- 调用方（审核服务）拿不到失败，没有任何重试、没有削峰；
- 表现是「审核通过了，但用户在公域刷不到」，且**没有任何告警信号**——
  这类「静默不一致」比显式报错危险得多。

## 二、改造内容

### 1. 事件契约
`tf-shared` 新增 `FeedTimelineEvent`（零依赖 record，同 `FeedItemView` 用 `-parameters` 反序列化）：
`mediaId` / `action` / `poolLevel` / `item` / `occurredAt`。

**刻意不提供 `isAppend()` / `isRemove()`**：record 上 `is` 前缀的布尔访问器会被 Jackson 当 getter
序列化，产出多余的 `"append":true,"remove":false` 字段，污染消息格式并埋下严格反序列化失败的隐患
（与 `Result<T>.isSuccess()` 同类非对称结构）。调用方改为比较 `ACTION_APPEND` / `ACTION_REMOVE` 常量。

### 2. 顺序性（不可妥协）
`append` 与 `remove` **共用同一 topic + 同一消费者**，靠消息体 `action` 区分；
**绝不按 action 拆 tag / 拆消费者**——否则同一帖的 `append` 与 `remove` 会落进不同队列、失去顺序保证，
出现「remove 先执行、append 后执行 → 已下架内容重新出现在公域」的**内容安全事故**。

- 发送侧：`syncSendOrderly(hashKey = timelineKey)` —— hashKey 用**帖身份**，同帖必落同一队列；
- 消费侧：`ConsumeMode.ORDERLY`，单队列串行消费。

### 3. 双路径（按开关条件装配，二者互斥）
网关抽象出 `FeedTimelinePublisher` 端口，两个实现靠 `@ConditionalOnProperty` 互斥激活：

| 实现 | 激活条件 | 行为 |
|---|---|---|
| `HttpFeedTimelinePublisher` | `turbofeed.mq.enabled=false`（`matchIfMissing=true`） | 保留 B1 同步 HTTP 兜底，**克隆即跑无需 MQ**；也是唯一仍直调 `FeedEngineClient` append/remove 的地方 |
| `RocketMqFeedTimelinePublisher` | `turbofeed.mq.enabled=true` | **fail-fast**：投递失败直接抛，不写任何降级/本地兜底 |

`RocketMqFeedTimelinePublisher` 不做降级的理由：降级会让 MQ 的持久化 / 重试 / 削峰**全部失效**，
正是 B2 要消灭的东西。不丢的保证由审核 / 删除路径的 `@Transactional` 回滚承担——
投递抛异常 → 事务回滚 → 状态不流转，失败是显式且可重试的。

### 4. 配置为什么用 profile
MQ 开关放 `turbofeed.mq.enabled`，但 RocketMQ 连接配置**放独立 profile 文件**
`application-mq.yml`（`--spring.profiles.active=mq`），而不是本文件里的空值占位：

> `rocketmq-spring` 的 `@ConditionalOnProperty(prefix="rocketmq", value="name-server")`
> **没有 `havingValue`**，属性「存在但为空」同样算命中 → 生产者 Bean 会被创建、启动时调用
> `producer.start()` 去连不存在的 NameServer。因此空值占位做不到「不创建 Bean」，
> **只能靠 profile 让属性真正缺席**。

NameServer 地址可用 `TURBOFEED_ROCKETMQ_NAME_SERVER` 覆盖。

## 三、自测
- 关闭 MQ（默认）全链路：上传 → 审核 → feed 出现 → 下架，走 `HttpFeedTimelinePublisher` 路径。
- 开启 MQ：`--spring.profiles.active=mq` + 本地 broker，验证同帖 `append`/`remove` 有序，
  以及引擎不可用时的 fail-fast 与事务回滚行为。
- 7 模块 `mvn compile` 通过。

## 四、遗留边界
- MQ 模式下 `@Transactional` 回滚保证「不丢」，但**不保证「不重」**：重投依赖消费端幂等
  （`FeedTimelineStore` 以 `timelineKey` 作幂等键 + 反查索引精确 `ZREM`），已具备。
- 引擎侧 `FeedTimelineConsumer` 的消费失败重试次数 / DLQ 策略沿用 `rocketmq-spring` 默认，
  尚未按业务 SLA 显式调参。

## 五、影响文件
- 新增：`FeedTimelineEvent`（tf-shared）、`FeedTimelinePublisher`、`HttpFeedTimelinePublisher`、
  `RocketMqFeedTimelinePublisher`、`FeedTimelineConsumer`（engine）、`application-mq.yml`（双侧）
- 修改：`FeedTimelineStore`、`MediaReviewService`、`MediaUploadService`、`FeedTimelinePublisher` 调用点、
  `docs/architecture/service-split.md`
