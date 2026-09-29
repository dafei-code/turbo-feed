# 0057 M0 行为明细落盘 —— 训练样本的唯一来源（含前端埋点补齐）

- 日期：2026-09-29
- 影响模块：`tf-shared`（契约）、`tf-gateway`（透传）、`tf-feed-engine`（落盘）、`turbo-feed-ui`（埋点）
- 类型：新增能力；**默认关闭**，无 DDL、无中间件新增

---

## 一、为什么这是 P3 二阶段的唯一硬阻塞

P3 二阶段规划了四项：双塔召回 / 精排模型 / 特征存储 / 粗排截断。
四项的共同前提都是**训练样本**，而样本只能来自行为明细。改造前：

- 行为事件只用于累加 Redis 计数器（`tf:post:stat:*`）与兴趣画像（`tf:user:interest`）；
- **聚合完即丢弃**——能回答"这条内容有多少赞"，回答不了"哪个用户在什么位置看到了什么、点了没点"。

更严重的是本次核查发现的第二个事实：

> **前端此前完全没有埋点上报**。`turbo-feed-ui/feed.html` 只有拉取列表的 `fetch`，
> 没有任何 `/api/feed/behavior` 调用 → Redis 里的 `impressions / playCompletes` 实际恒为 0。

也就是说：流量池赛马（0053 热点召回依赖它）、兴趣画像（0051）、负反馈（0052）
此前都是**空转**的——服务端管线完整，但没有输入。

---

## 二、契约扩展：`requestId` + `position`

`FeedBehaviorEvent`（tf-shared）与 `BehaviorReport`（tf-gateway）各新增两个字段：

| 字段 | 作用 | 缺失后果 |
|---|---|---|
| `requestId` | 本次推荐请求 ID（客户端拉列表时生成，同一次刷新共用） | 无法把"当次展示了哪些内容"归组 → **「曝光了但没互动」无法判定为负样本**，也没有候选集合可还原 |
| `position` | 该条目在本次结果里的位次（0 起） | 无法做 **position bias 校正** → 模型把"位置靠前"误学成"内容更好" |

**刻意不落 `score`（服务端排序分）**：分数随模型/权重版本变化，历史不可比，落盘反而污染训练集；
训练时应由**特征重算**得到——这正是特征存储要解决的核心问题（训练-serving 一致性）。

两者都可为 `null`（旧端兼容），缺省**不影响**既有统计与画像逻辑。

---

## 三、落盘实现：`BehaviorLogSink`（tf-feed-engine）

新增 `com.turbofeed.feedengine.logging.BehaviorLogSink`：

- **格式**：JSONL，按天分文件 `feed-behavior-{yyyyMMdd}.jsonl`，一行一事件
  （`ts / userId / timelineKey / type / watchDuration / mediaDuration / requestId / position`）。
- **异步**：单工作线程 + 有界队列（`ArrayBlockingQueue`）。队列满**直接丢弃并计数**，
  不阻塞调用方、不抛异常——丢埋点损失样本量，卡请求损失可用性。
- **fail-open**：统计与画像各自独立写入，本类挂掉不影响它们。
- **默认关闭** `turbofeed.feed.behavior-log.enabled=false`：
  落盘是"为了攒数据"的行为，不该在任何人的机器上静默写文件。

### 为什么先落文件而不是直接进 ClickHouse / Kafka

| 理由 | 说明 |
|---|---|
| 零新中间件 | 当前只有 MySQL + Redis + RocketMQ；JSONL 是"最容易被任何下游吃掉"的格式 |
| 解耦 | 落盘与"导入哪里"是两个决策，先保证明细不丢 |
| 可验证 | 文件能被校验脚本直接读回，落 ClickHouse 则难以在单机上做闭环断言 |

**已知取舍**：单机文件不保证多副本，需后续导入对象存储/数仓兜住持久性——这是取舍，不是遗漏。

### 接线点（两条路径必须成对修改）

- `FeedTimelineController#behavior`（HTTP 兜底路径）
- `FeedBehaviorConsumer#onMessage`（MQ 路径）

两边都落到"统计 + 画像 + 落盘"三件事，任一侧漏改会造成**埋点路径不一致**
（典型症状：默认环境有数据、开了 MQ 反而没样本）。已在两侧 javadoc 互相标注。

---

## 四、前端埋点补齐（turbo-feed-ui/feed.html）

新增埋点模块并接入 5 类事件：

| 事件 | 触发时机 | 备注 |
|---|---|---|
| `IMPRESSION` | 进入某条（首条渲染 / 滑动切条 / 上下键切换） | 带 `position` |
| `WATCH` | 离开某条时结算**停留时长** | 图文无真实时长，用"期望驻留时长 8s"当分母算完播率 |
| `LIKE` | 点赞（**不报取消点赞**） | 见下 |
| `COMMENT` | 发送评论 | |
| `SHARE` | 分享给朋友 | 服务端热度权重最高（2.0） |

设计决策：

- **只上报「推荐」Tab**：『我的』是作者看自己的作品，不是公域曝光，报进去会污染样本。
- **不报"取消点赞"**：契约无 `UNLIKE` 类型；把取消记成负向会与「不感兴趣」语义混淆——
  后者是"别再给我看这个"，前者只是手滑收回。
- **攒批上报**（2s 间隔 / 满 50 条即发）+ `keepalive: true`，保证关页面时最后一批也发得出去。
- **顺序**：`onEnterItem(idx)` 先结算上一条的停留（WATCH），再报本条曝光（IMPRESSION）——
  顺序反了"看了多久"会记在错误的条目上。

### 未做（明确的产品缺口）

**「不感兴趣」没有独立 UI 入口**：`btnMore` 目前是"举报 / 不感兴趣 / 下载"的合并占位。
后端 `NOT_INTERESTED` 已支持（0052），需要产品侧给出独立入口后再接。

---

## 五、验证

`target/verify/BehaviorLogVerify.java`（真文件系统 + 真 Redis 集群）**17/17 PASS**，核心断言：

| 组 | 断言 |
|---|---|
| ① | 启用后按天生成文件、显式 `flush()` 后内容可见、4 行完整、含 `ts/userId/timelineKey/type/requestId/position`、顺序 FIFO |
| ② | `enabled=false` 时**不创建目录与文件**、`written/dropped` 恒为 0 |
| ③ | 队列满时 500 条写入 16ms 返回（不阻塞）、溢出行计入 `dropped` |
| ④ | **对账**：JSONL 聚合计数 == Redis `tf:post:stat:*`（LIKE 3/3、COMMENT 2/2） |
| ⑤ | `close()` 排干队列，尾部 5 条不丢 |

回归：`target/verify/run_all_feed.sh` 一次跑完 6 个脚本，**汇总 PASS=64 FAIL=0**（连跑两轮一致）
——负反馈 10、热点召回 11、打散 6、评分模型 8、缓存隔离 12、行为落盘 17。

---

## 六、下一步

有了明细落盘，才谈得上：

1. **M1 特征服务**：在线/离线共用同一份特征代码（`tf:feed:feat:`），消除训练-serving 偏差；
2. **M2 精排模型**：LR/GBDT 替换当前线性手调权重（新增 `RankingModel` 实现即可，0055 已留好接口）；
3. 双塔召回 / 粗排截断继续保持后置——量级没到，先不引 ANN 与向量库。
