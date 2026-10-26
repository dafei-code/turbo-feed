# 跨服务契约演进机制（M2 / 4.1）

> 日期：2026-10-26
> 范围：tf-shared 里的跨服务契约（网关 ⇄ 引擎 ⇄ 计数器）如何演进、兼容性如何判定、谁负责校验。
> 配套：`SharedContractJsonTest`（golden 串 + 幽灵字段护栏 + 版本协商）。

---

## 1. 为什么要有这份文档

契约是一种**跨部署单元、跨时间**的协议：网关今天发的消息，可能由引擎下周的代码来消费；
写进 Redis 时间线的成员串，可能半年后还在被读。它的特殊性在于——

**破坏它的改动不会让编译失败，也不会让单测变红。**

字段改名、语义翻转、删字段，这些操作在本地跑得通、CI 全绿，只有线上消息解析时才炸，
而且往往以「按错误语义处理了但看起来正常」的形式出现。这正是本项目反复出现过的失效形状
（Redis 拓扑双份配置、双通道副作用各写一遍），契约层只是它的第三个副本。

因此这里把三件事写死：**契约长什么样（golden）、怎么判断能不能消费（版本）、校验挂在哪（单一入口）**。

---

## 2. 契约清单与当前版本

| 契约 | 载体 | 是否持久化 | 当前版本 | 生产侧构造方式 |
|---|---|---|---|---|
| `FeedTimelineEvent` | MQ 顺序消息（网关 → 引擎） | 否（消费完即弃，但存量消息可能滞留） | 1 | 工厂 `append()` / `remove()` |
| `FeedBehaviorEvent` | MQ 并发消息（网关 → 引擎） | 否 | 1 | 7 参便捷构造器 |
| `FeedItemView` | 内嵌于上述事件；**并序列化进 `tf:feed:tl:*` 成员串** | **是**（长期驻留 Redis） | 1 | 9 参便捷构造器 |
| `CounterIncrementEvent` | HTTP / MQ（网关 → tf-counter） | 否 | 1 | 规范构造器 |
| `CounterView` | HTTP 响应（tf-counter → 网关） | 否 | 1 | 规范构造器 |
| `PlayableView` | HTTP 响应（引擎 → 网关） | 否 | 1 | 工厂 `playable()` / `blocked()` |

**`FeedItemView` 为什么要单独定版本**：它的持久化寿命远长于承载它的事件。事件被消费掉之后，
成员串还在 Redis 里；半年后读它时，信封（事件）早就没了，只靠信封版本救不了它。

---

## 3. 版本协商规则

实现：`com.turbofeed.shared.model.ContractVersion`。规则只有一条硬边界：

| 收到的版本号 | 判定 | 理由 |
|---|---|---|
| 缺失（`null`）/ 非正值 | **容忍**，按首版语义处理 | 本字段引入**之前**产生的存量数据不可能回溯补写：MQ 里未消费的旧消息、Redis 里已物化的成员串。把它们拒掉等于上线即事故 |
| ≤ 当前版本 | **容忍** | 向下兼容是硬约束。新增字段可空，旧版本消息按缺失处理 |
| \> 当前版本 | **拒绝**，抛 `UnsupportedContractVersionException` | 生产者已升级、消费者未升。继续解析必然按错误语义处理 |

### 3.1 「跨 major 拒绝」的边界在哪里

本项目用单个 `int` 表示版本，没有 major/minor 之分，因此「跨 major」=「版本号大于当前」。
这意味着**任何版本递增都是破坏性的**，代价偏高；但对本项目是合适的：

- 契约字段总数是个位数，不存在「每月加一个字段」的演进压力；
- 真正需要的区分只有两级：「我能解释」和「我不能解释」。引入 minor 语义（1.3 能解释 1.5 的新字段）
  需要为每个字段单独声明兼容区间，复杂度远超收益；
- **加可空字段不需要递增版本号**——未知字段默认忽略，正反两个方向都安全
  （由 `SharedContractJsonTest#未知字段被忽略` 与 `#feedItemView_历史串反向兼容` 锁住）。

### 3.2 递增版本号的发布顺序

**先升消费者，再升生产者。** 反过来的中间窗口里，新版本消息会被旧消费者全部拒绝——
对时间线事件意味着 append/remove 进 DLQ，对行为埋点意味着画像数据断流。

这个顺序**没有机制保证**，靠发布手册。R8 的事务闭合、R7 的单一入口都不覆盖它。

---

## 4. 校验挂在哪里

| 位置 | 契约 | 通道 | 不兼容时的行为 |
|---|---|---|---|
| `FeedTimelineDeliveryService#deliver` | `FeedTimelineEvent` + 内嵌 `FeedItemView` | HTTP 兜底 + MQ 顺序 | 见下 |
| `FeedBehaviorConsumer#onMessage` | `FeedBehaviorEvent` | MQ 并发 | 抛异常 → 重试耗尽进 DLQ |
| `TimelineSupport#parse` | `FeedItemView` | 时间线读路径 | 返回 `null`（跳过这一条） |

**为什么挂在 `FeedTimelineDeliveryService` 而不是两个门面**：它是 M1/R7 建立的
**两个通道的唯一副作用入口**。若把版本校验放在各自门面（Controller / Consumer），
就会重现「HTTP 通道校验了、MQ 通道忘了」的静默分歧——那正是这个类被创造出来要消灭的缺陷形状。
放一处，两条通道自动都覆盖。

**写路径与读路径的口径差异（重要）**：

- **写路径**要**拒绝并留痕**：`UnsupportedContractVersionException` 继承 `IllegalArgumentException`，
  复用消费端已有的「拒绝但不改动任何数据」分支——
  `apply()`（HTTP 兜底）catch 后只告警不改动数据；`applyStrict()`（MQ）上抛 → RocketMQ 重试耗尽进 DLQ，可巡检。
  用继承而不是新增异常类型，是为了让它自动落进**两条通道都认识**的那条分支，
  而不是新增一条只有一半通道认识的分支。
- **读路径**要**跳过而不失败**：时间线翻页时单条成员串不兼容，正确处置是丢这一条（`parse` 返回 `null`），
  而不是让整页 Feed 失败。

两者的共同底线是同一条：**绝不硬解**。

---

## 5. 兼容性矩阵

`SharedContractJsonTest` 锁住的矩阵（横=生产者版本，纵=消费者版本）：

| | 消费者 v1 | 消费者 v2（未来） |
|---|---|---|
| **生产者无版本（存量）** | ✅ 容忍，按 v1 语义 | ✅ 容忍 |
| **生产者 v1** | ✅ 正常 | ✅ 容忍（向下兼容） |
| **生产者 v2（未来）** | ❌ 拒绝（抛异常 / 跳过该条）<br>由 `applyStrict_futureSchemaVersion_*` 与 `parse_高版本成员串_*` 锁住 | ✅ 正常 |

**已物化的历史成员串**（无 `postId` / `images` / `tags` / `schemaVersion`）必须始终可解析，
缺失字段走回退语义：`timelineKey()` 退 `mediaId`、`imageUrls()` 退 `[url]`。
这是硬约束——Redis 里的存量数据不打算迁移。

---

## 6. 已订正的错误结论：`-parameters`

> **原结论**（写于早期，见于 tf-shared `pom.xml` 注释、各 record javadoc、
> `service-split.md` §8/§9）：契约 record 的 JSON 反序列化依赖 `-parameters` 编译参数，
> 否则形参名编译成 `arg0/arg1`，Jackson 无法绑定。

**实测证伪（2026-10-26）**：用一个**不注册 `ParameterNamesModule`** 的裸 `ObjectMapper`
对全部六个契约做序列化 + 反序列化往返，**全部通过**。

原因：Jackson 2.12+ 对 record 走 `Class#getRecordComponents()` 反射取组件名，
与 `ParameterNamesModule`（读 `Parameter#getName()`，服务于**普通类**的构造器绑定）不是同一条路径。
原结论是把普通类的经验套到了 record 上。

| 项 | 处置 |
|---|---|
| `<parameters>true</parameters>` | **保留**（零运行时成本；非 record 类的构造器绑定与反射诊断仍需要），但注释已改为准确说明，不再声称它是 record 序列化的前提 |
| 各 record javadoc | 已订正为「经 `getRecordComponents()`，不依赖 `-parameters`」 |
| `service-split.md` §8/§9 | 已加「结论更正」批注 |
| 防回归 | `SharedContractJsonTest#record不依赖编译期形参名`：误关编译参数会在 CI 变红 |

**为什么不干脆删掉这个编译参数**：删除的收益（少一行配置）小于风险（未来有人新增走构造器绑定的
普通类时踩坑且无提示）。保留但把理由写对——不写清楚，下一个人既不敢删、也不敢信。

---

## 7. 已知未收口的旁路解析点

`FeedItemView` 除 `TimelineSupport#parse` 外，还有 4 处直接 `objectMapper.readValue(..., FeedItemView.class)`，
它们**没有**走版本协商：

| 位置 | 路径 | 性质 |
|---|---|---|
| `recall/VectorIndexWriter` | 向量索引离线写入 | 旁路，异常 fail-open 跳过 |
| `recall/TagCooccurrenceTrainer` | 标签共现离线训练 | 旁路（离线），异常 fail-open 跳过 |
| `timeline/RecommendationPipeline` | 推荐漏斗内解析 | 在线读路径 |
| `timeline/RecommendedFeedService` | 推荐流缓存反序列化 | 在线读路径 |

**为什么暂未收口**：这几处分布在召回 / 训练 / 推荐三个包，`TimelineSupport` 是 `timeline` 包可见，
提升可见性或新建公共解析入口属于「再抽一层」的重构，与 M2 当前批次（契约可靠性）边界不符，
且它们各自的 fail-open 语义需要先逐一确认。

**风险评级：低**。这几条路径的共同点是失败即跳过该条（不影响整页、不写坏数据），
与 `parse` 的处置一致；真正需要「拒绝并留痕」的是写路径，而写路径已 100% 覆盖。
列为 M3 收口项。

---

## 8. 变更契约时的操作清单

1. **加可空字段** → 直接加，不必递增版本号。同步更新 `SharedContractJsonTest` 的 golden 串
   （测试会红，这是**预期行为**，正是它存在的意义）。
2. **不兼容变更**（改名 / 改语义 / 删字段）→ 递增 `SCHEMA_VERSION`，按 §3.2 的顺序发布。
3. 无论哪种，都**不要**给契约类加 Jackson 注解——命中 Jackson 默认行为的坑一律从命名上绕开
   （`is`/`get` 前缀会被当属性，见 `Result#succeeded()`），以保住 tf-shared 的零第三方依赖定位。
4. 跑 `mvn -o test`：`SharedContractJsonTest` 的「幽灵字段护栏」会拦住任何多出来/少掉的字段。
