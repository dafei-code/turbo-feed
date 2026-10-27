# ADR-0005：跨服务契约版本协商

- **状态**：已采纳（Accepted，2026-10-26）
- **所属改进项**：`improvement-plan.md` §3 / M2-4.1
- **决策人**：dafei
- **关联 ADR**：[0002](0002-nacos-service-discovery.md)（扩实例后新旧版本会同时在线，这条才真正必要）

## 1. 背景（Context）

三个跨服务 record —— `FeedItemView`（时间线成员）、`FeedTimelineEvent`（MQ 事件）、
`FeedBehaviorEvent`（行为埋点）—— 是网关与引擎之间的全部数据接口。它们的 JSON 字段名
**就是协议本身**。

改造前的协议演进只有一种方式：**同时发布**。一旦做不到同时（滚动发布、灰度、回滚到旧包），
就会出现「生产者按新语义写、消费者按旧语义读」：字段缺失时 Jackson 用默认值填、字段
多出来时被静默忽略，**不报错**。这类错误最难查——系统看起来在正常工作，只是数据悄悄错了。

一个具体的失效形状：给 `FeedItemView` 加一个必填语义的字段后回滚旧版消费者，
旧代码拿不到该字段 → 用 `null` 填充 → 时间线里出现一批「看起来正常但语义缺失」的条目，
没有任何告警。等发现时已经写进了 Redis 读模型。

## 2. 决策（Decision）

1. **给三个契约加 `schemaVersion` 组件**（`int`），版本号真值只有一份（各 record 的
   `SCHEMA_VERSION` 常量），不散到生产点。
2. **新增 `ContractVersion.requireConsumable` / `isConsumable`** 做协商：
   - `seen == null || seen <= 0` → 放行（**容忍存量数据**：加版本号之前写进去的数据没有这个字段）；
   - `seen <= supported` → 放行；
   - `seen > supported` → **拒绝消费**。
3. **写读两条路径口径不同**（这是本决策最关键的一点）：
   - **写路径**（`FeedTimelineDeliveryService#deliver`，双通道唯一入口）：抛
     `UnsupportedContractVersionException`，让消息进重试 / DLQ，**留痕**；
   - **读路径**（`ItemViewCodec`、`TimelineSupport`）：返回 `null` **跳过这一条**，
     保证整页仍能渲染。
   - 共同的底线：**绝不硬解**——不按旧语义解释新数据。
4. **`UnsupportedContractVersionException` 继承 `IllegalArgumentException`**：复用消费端
   已有的「拒绝但不改动数据」分支，让 HTTP 兜底与 MQ 两个通道自动都生效，不必逐个通道改。
5. **解析入口唯一化**：`ItemViewCodec`（引擎）+ `ContractVersion.filterConsumable`（网关），
   并用 ArchUnit 规则钉死「除 `ItemViewCodec` 外不得调用 ObjectMapper 反序列化」。

## 3. 后果（Consequences）

**正面**
- 滚动发布 / 灰度 / 回滚期间不会「静默按错误语义处理数据」，最坏是丢一条而不是错一片；
- golden JSON 测试（`SharedContractJsonTest`，13 例）把字段名锁死，改字段不更新测试即红；
- 版本号只有一份真值，新增字段时忘升版会被 review 抓出来（因为它就写在契约类里）。

**负面 / 成本**
- **读路径会静默丢条目**：高版本数据进来时那一页少一条，只有日志没有告警。
  权衡后接受——丢一条远好于把错误语义渲染给用户；但这也意味着排查「为什么少了一条」
  时要去 grep 版本不兼容日志。
- **没有自动升级/降级转换**。版本不兼容就是拒绝，不做「v1 ← v2 的兼容适配」。
  真正需要跨大版本兼容时得再设计（届时很可能要引入显式转换器）。
- 存量数据（无版本号）被无条件放行，因此**版本号无法用于「识别老数据」**——
  它只做「识别比我还新的数据」这一件事。

## 4. 备选方案

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 不做版本号，靠「只加字段不删不改」的约定 | **否决** | 约定无法在构建期检查，也挡不住语义变化（字段名不变、含义变了同样是破坏） |
| 加版本号 + 读路径也抛异常 | **否决** | 一个高版本条目会让整页 500，把「一条数据的问题」放大成「整页不可用」 |
| protobuf / Avro 等 IDL + 代码生成 | **暂缓** | 能彻底解决，但要引 schema registry 与代码生成链，与当前模块规模不匹配 |
| 只给 MQ 事件加版本号 | **否决** | 时间线成员串（`FeedItemView`）是跨进程读模型，同样是契约，漏了它等于没做 |

## 5. 落地记录（2026-10-26 ~ 10-27）

- **已落地**：三个 record 加 `schemaVersion`；`ContractVersion`（含 `filterConsumable`）；
  三条校验挂点；`SharedContractJsonTest`(13) / `ItemViewCodecTest`(7)；
  解析入口收敛为 `ItemViewCodec` + ArchUnit 门禁（已做非空洞性反证）。
- **一个被推翻的旧结论**：此前文档写「record 反序列化依赖 `-parameters` 编译参数，不开就抛
  `InvalidDefinitionException`」。实测证伪：Jackson 2.12+ 通过 `Class#getRecordComponents()`
  取组件名，**不依赖**该参数；裸 `ObjectMapper`（连 `ParameterNamesModule` 都不注册）
  往返六个契约全部通过。代码注释与文档已订正，并有一条测试固化它。
- **仍存在的旁路**：网关侧没有与引擎等价的 ArchUnit 门禁（引擎有）。风险低（网关解析点已
  收敛到一处），登记在 `contract-evolution.md` §7.3。
- 设计细节（兼容性矩阵、版本协商边界用例、如何新增字段）见
  [`docs/architecture/contract-evolution.md`](../architecture/contract-evolution.md)。
