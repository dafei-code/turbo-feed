# 0042 本地缓存跨实例失效：Redis pub/sub 广播

## 问题：本地缓存把不一致窗口拉长到整个 TTL

0039 引入的 `AccountStateCache` 是**进程内**缓存。多实例部署时会出现：
A 实例封禁了某账号，B 实例仍认为自己缓存里的「可写」有效——
这个不一致窗口是**整个 TTL（默认 15s）**。对「封禁后立即生效」这类语义来说太长。

## 方案：写方广播、订阅方只失效本地

| 角色 | 实现 |
|---|---|
| 发布 | `AccountStateCache#invalidate`：先 `invalidateLocal`，再 `convertAndSend(channel, instanceId + ":" + userId)` |
| 订阅 | `RedisCacheInvalidationConfig`：`RedisMessageListenerContainer` 订阅同一频道，回调 `onInvalidateMessage` |
| 回环防护 | 消息体带 `instanceId`，收到自己发的直接丢弃 |

三个设计取舍：

- **为什么是 pub/sub 而不是「把缓存搬到 Redis」**：搬到 Redis 等于把「本可不发生的读」
  换成「每次必打的网络往返」，与本地缓存的初衷（消除 DB 读）南辕北辙。
  pub/sub 只在**状态变更**时产生流量，而封禁/扣分是极低频操作。
- **广播失败必须可退化**：`convertAndSend` 异常被吞掉回退到 TTL 兜底——
  缓存是加速手段，**绝不能反向拖垮写路径**。因失败频率可能很高，记 debug 不记 warn。
- **订阅侧绝不再次广播**：否则多实例间形成消息回环风暴，故订阅回调走 `invalidateLocal`。

发布点沿用 0039 已埋好的 5 处（`AccountCreditService` 3 处 + `PenaltyService` 2 处），
本次未改业务代码，只补订阅侧与开关：`turbofeed.state-cache.broadcast-enabled`（默认 true，
关闭时发布侧与订阅侧同步停用）。

## 验证：进程内双实例集成测试（含关广播对照组）

同 JVM 内造两个「实例」A / B，各有独立 `instanceId` 与订阅容器，
用「loader 是否被重新调用」判断缓存是否真的失效：

```
=== 场景 1：开启广播 ===
  PASS B 缓存命中（loader 未被调用）                     :: level=L1
  PASS A 广播后 B 缓存已失效（loader 被重新调用）         :: B 读到 L2
  PASS 非本实例消息会被处理                               :: 返回 true
  PASS 空/非法消息被安全忽略                              :: null/空/无分隔符/非数字 均 false
=== 场景 2：对照组，关闭广播 ===
  PASS 关广播时 B 仍读到旧值                              :: B 读到 L1  ← 关键
ALL PASS
```

对照组是关键：它证明场景 1 的失效**确由广播造成**，而不是 TTL 恰好到期或缓存根本没生效。

补充：真实网关启动时确认 `已订阅账号状态缓存失效频道: turbofeed:cache:account-state:invalidate`。

## 遗留

跨实例最长不一致已压到「一次 Redis 往返」，但**不是强一致**：
Redis 整体不可用期间仍退化到 TTL 上界（15s）。这是刻意接受的——
TTL 才是下界保障，广播只是把窗口从 15s 压到毫秒级。
