# 0040 发件箱补全：所有时间线投递路径统一走 outbox

## 背景：0037 只接了一条线

0037 落地发件箱时只改了 `reviewByMediaId`（人审通过）这一条投递路径，
其余四处仍是「直接调 publisher、失败就丢」的老写法：

| 路径 | 类型 | 丢失后果 |
|---|---|---|
| `handleUploaded` 先发后审自动通过（3 处分支） | APPEND | 内容已 APPROVED 却没进流量池 |
| `handleAppeal` 申诉翻案恢复公域 | APPEND | 翻案成功但内容仍不展示 |
| `removeFromTimeline`（高危举报下架 / 申诉中） | REMOVE | **内容已下架，公域仍在展示** |
| `MediaUploadService#delete` 用户删除 | REMOVE | **内容已删除，公域仍在展示** |

**REMOVE 类丢失比 APPEND 类更严重**：APPEND 丢了是「新内容没露出来」（少展示，可补），
REMOVE 丢了是「违规/已删内容继续展示」（安全事故，且没人知道）。
把两条 REMOVE 路径补齐，是本次收益最大的部分。

## 改动

- `MediaReviewService` 新增两个私有方法 `publishAppend` / `publishRemove`，
  全部 5 处投递改为「同事务落事件 + 提交后直投 + 中继补偿」。
- `MediaUploadService#delete` 加 `@Transactional`，使「逻辑删除 + REMOVE 事件行」原子提交
  （改造前该方法是无事务的，物理删 → 逻辑删 → 投递，任一步失败都留下不一致）。
- 两处 `FeedTimelinePublisher` 依赖随之**移除**：`MediaReviewService` 与 `MediaUploadService`
  已不再直接调用它，投递统一收敛到 `OutboxService`，避免后来者又去直接调。

### 顺序安全（REMOVE 会不会被后来的 APPEND 覆盖？）

不会。两点保障：

1. 事件按雪花 id 有序消费，APPEND 与 REMOVE 的先后即真实发生顺序；
2. 正常路径下 APPEND 在过审时就已经**直投**完成，REMOVE 一定晚于它发生。

因此不存在「remove 先到、append 后到把内容又放回来」的情况。

### 一个刻意的取舍：删除从「同步」变「提交后直投」

`delete` 原来是同步调引擎；现在改成提交后直投（失败由中继重投）。
代价是「删除请求返回后，公域可能还有毫秒级残留」；
收益是「再也不会出现删了却永远撤不掉的残留」。安全动作宁可晚、不可丢。

## 验证（真机全链路，MQ profile）

用一个新注册账号（L0 先审后放）完整走一遍治理闭环，四条事件全部 `SENT`：

```
18:03:40 发件箱投递成功: id=377062142895067136  TIMELINE_APPEND  ← 管理员审核通过
18:04:15 发件箱投递成功: id=377062290723311616  TIMELINE_REMOVE  ← 高危举报确认违规，整帖下架
18:04:57 发件箱投递成功: id=377062467970404352  TIMELINE_REMOVE  ← 作者申诉，整帖暂不可见
18:05:06 发件箱投递成功: id=377062503785566208  TIMELINE_APPEND  ← 管理员申诉翻案，恢复公域
```

业务侧返回值同步验证：`APPROVED → TAKEN_DOWN → APPROVED`，状态机与事件一一对应。

## 现状

**时间线投递已无 fail-open 直调**——全部经过发件箱，丢失可查（PENDING/FAILED/DEAD 行）、可补偿（中继重投）。

仍待做（与发件箱无关的部分）：
- 128 分片的 yaml 未同步 `user_phone_router` / `outbox_event`（P0-7，切换前必须补）
- Redis 真上集群需先补节点（3 主 3 从）
- 本地缓存跨实例失效需 Redis pub/sub 广播（当前最长 15s 不一致窗口）
