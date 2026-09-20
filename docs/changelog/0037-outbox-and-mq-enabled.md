# 0037 开启 RocketMQ + 补 P0-3 事务发件箱

## 一、outbox 是什么（一句话）

**把「DB 事务」与「投递消息」的分布式原子问题，降维成「本地事务原子 + 至少一次投递 +
消费端幂等」。** 事件行与业务数据写在**同一个本地事务**里（同生共死），提交后由中继投出去，
投失败就留着下次再投。于是「消息丢失」变成「消息可能重复」，而重复可由消费端消化。

## 二、解决的问题：改造前的 fail-open

`MediaReviewService#reviewByMediaId` 原写法是**事务提交后再投递**，失败只记一行日志：

```java
afterCommit(() -> {
    try { feedTimelinePublisher.append(post, level.poolLevel()); }
    catch (Exception e) { log.error("...待 outbox 补偿"); }   // ← 然后就没有然后了
});
```

后果：DB 已提交、内容已 APPROVED（用户可见），但**消息永久丢失**——帖子永远进不了公域时间线，
且无人重试、无记录、无法对账。这是典型的「失败没有去处」。

反向做法（先投递再提交）更糟：投递成功、事务回滚 → 时间线上出现一条 DB 里根本不存在的帖子。

## 三、方案

### 表：`outbox_event`（单表 ds_0）

队列表，按 id 顺序消费；不按用户分片，是为了让「待投递行数 / DEAD 行数」这类对账查询
一条 SQL 就能看到全貌。

### 状态机

```
PENDING ──领取(CAS)──▶ SENDING ──成功──▶ SENT(终态)
   ▲                      │
   │                      └──失败──▶ FAILED ──退避到期──▶ 再次被领取
   │                                    │
   │                                    └──attempt ≥ max──▶ DEAD(终态, 人工)
   └──── SENDING 卡死(进程崩溃) 超时 ────┘
```

- **SENDING 这一态不可省**：靠 CAS 把「待投递」变成「投递中」，多实例中继才不会重复投同一行；
  崩溃残留由 `reapStuck`（超时回收）兜住，不会永久悬停。
- **至少一次**：CAS 领取失败即跳过（幂等第一道防线）；真正的幂等由消费端保证
  （本项目 `FeedTimelinePublisher#append` 已幂等：同一帖重复投递先摘旧位置再写新位置）。

### 两条投递路径，一个实现

1. **快路径**：事务提交后直投一次（`deliverAfterCommit`），绝大多数请求在此完成，低延迟；
2. **补偿路径**：`OutboxRelay` 每 2s 轮询，取到期行重投。

二者共用同一个 `OutboxService#tryDeliver`——**补偿路径必须与正常路径走完全相同的代码**，
否则补偿逻辑会因长期不被执行而腐化，等真需要它时已经不可用。

### 快路径失败为什么不抛异常

此时事务已提交，抛异常既撤不回事务，又把「可补偿的失败」变成「用户可见的 500」。
正确动作是记日志 + 留行待补偿——这正是发件箱相对「catch 一下就完了」的本质区别：**失败有去处**。

## 四、开启 MQ（RocketMQ）

`--spring.profiles.active=mq` + `TURBOFEED_MQ_ENABLED=true`，NameServer 已在 `docker-compose.yml`
（namesrv 9876 + broker 10911）。默认仍关闭，保持「克隆即跑」。

## 五、验证（真机实证，MQ profile 启动 + 真实请求）

**1. MQ 真的起来了**

```
o.a.r.s.a.RocketMQAutoConfiguration : a producer (turbofeed-media-producer-group) init on namesrv 127.0.0.1:9876
DefaultRocketMQListenerContainer    : running container: consumerGroup='turbofeed-media-review-group',
                                      nameServer='127.0.0.1:9876', topic='turbofeed-media-uploaded'
GatewayApplication                  : Started GatewayApplication in 9.718 seconds
```

**2. 中继在跑且路由正确**（`outbox_event` 单表 → ds_0，无 TableNotFoundException）

```
Actual SQL: ds_0 ::: SELECT id FROM outbox_event WHERE status IN (PENDING, FAILED) AND next_attempt_at <= ? ORDER BY id LIMIT ?
```

**3. 成功闭环**：手工塞一条 `TIMELINE_APPEND`（引擎故意不通）

```
发件箱补偿一轮: due=1, 成功=1, 失败=0
发件箱投递成功: id=..., type=TIMELINE_APPEND, aggregateId=media/1/probe.jpg
DB: status=SENT  attempt=1/5  last_error=NULL
```

同时证明 payload 的 JSON 往返可用（`MediaItem` 显式 `@JsonProperty`，反序列化成功）。

**4. 失败闭环**：塞一条契约不符的 payload（必然抛 `MismatchedInputException`）

```
DB: status=DEAD  attempt=3/3  last_error=com.fasterxml.jackson.databind.exc.MismatchedInputException...
```

领取 → 失败 → 退避 → 重试 → 达上限判 DEAD 留待人工对账，全链路符合设计。

## 六、改动清单

- 新增 `OutboxEventRepository`（insert / findById / findDueIds / claim / markSent / markFailed / reapStuck）
- 新增 `service/event/outbox/`：`OutboxEventType`、`OutboxService`（落事件 + 分发投递）、
  `OutboxRelay`（轮询补偿）、`TimelineAppendPayload`、`TimelineRemovePayload`
- `MediaReviewService#reviewByMediaId`：时间线投递改为「同事务落事件 + 提交后直投」，
  删掉原来只记日志的 afterCommit 块
- `init-local.sql` 建表；`shardingsphere-config.yaml` 登记 `ds_0.outbox_event`
- `application.yml` 新增 `turbofeed.outbox.*`（max-attempts / backoff / batch / poll / stuck-timeout / relay-enabled）
- `MediaReviewService` 改显式构造器 + 显式 Logger（本机 Lombok 坑）

## 七、已知边界 / 后续

- 申诉翻案路径（line ~346）的 `append` 仍是老的直接调用，尚未接入发件箱——下一步统一。
- `TIMELINE_REMOVE` 类型与分发分支已就绪，但下架/删除路径尚未改为走发件箱。
- DEAD 行不会自动清理（刻意保留供人工对账），确认无需补偿后自行归档删除。
- 中继每轮会执行 1 次 `reapStuck` 的 UPDATE（空表也执行）；当前 2s 一轮、单表小更新，成本可忽略。
