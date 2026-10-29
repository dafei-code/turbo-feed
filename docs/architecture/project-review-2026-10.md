# turbo-feed 项目评审报告（架构 / 代码 / 业务）

> 评审日期：2026-10-29 · 范围：`/Volumes/D/workbuddy/turbo-feed` 全量代码与文档
> 方法：通读架构文档（overview / service-split / failure-mode-ledger / sharding / threat-model）+
> 全模块代码统计 + 关键实现抽样（InterestService / MediaLikeService / MediaReviewService / 排序与召回包）。
> 结论分级：✅ 优势 · 🟡 风险/待改进 · 🔴 必须处理。

---

## 0. 总体结论

这是一个**工程成熟度明显高于一般 demo 的项目**：微服务边界清晰、失败模式有显式账本、
分片与一致性设计严谨、文档与代码同仓且诚实（README 主动订正过时宣传）。
当前主要短板不在"设计错误"，而在 **① `tf-benchmark` 空壳 + `tf-hotspot` 半残（counter 已落地）、
② 网关偏胖（`MediaReviewService` 上帝类）、③ 数据度量闭环未闭环（behavior-log 关、无 A/B）、
④ HA 与告警是"代码已支持但部署未启用"**。
整体评级：**架构设计 B+ / 代码实现 B+ / 业务完整度 B-**，可继续演进，无颠覆性返工风险。

规模基线：网关 243 文件 / 26.4k 行，引擎 99 文件 / 10.2k 行，shared 1.3k 行，测试 73 个文件。

---

## 1. 架构设计评审

### ✅ 做得好的

1. **服务拆分与故障域清晰**：网关(8080)/引擎(8083)/计数(8081)三可执行服务；契约下沉零依赖的 `tf-shared`；
   同步调用面只有 1 个读接口，其余写路径全异步化——这是"为什么不需要 RPC"的正确判断。
2. **失败模式有显式账本**（`failure-mode-ledger.md`）：六条异步链路逐条写清"会丢什么/会重什么/怎么发现"，
   区分"至少一次（资损链路）"与"至多一次（埋点链路）"两种取舍。这是很多生产系统都没有的。
3. **一致性设计严谨**：outbox 与业务数据同事务落库；RocketMQ 顺序消息（append/remove 同 topic 同消费者，
   拒绝拆 tag 导致"下架内容复活"）；CAS 状态机防 TOCTOU 与死锁。
4. **降级口径正确**：引擎不可用默认 `empty`（拒绝跨分片广播回源），把"一次依赖故障"和"全库故障"解耦。
5. **安全内建**：JWT + ThreadLocal（userId 不进方法签名防越权）、RBAC 按权限而非角色、
   `/internal/**` 双向 `X-Internal-Token`（非 dev/local profile 缺令牌即启动失败）、敏感文件 Magic Number 白名单。
6. **可扩展性留缝**：排序 `RankModelType`(LINEAR/DEEPFM/MULTI_TARGET) + `RankModelClient`、
   召回四路（热点/兴趣/流量池/向量）+ RediSearch KNN——模型化是插拔替换，不是重写。

### 🟡 风险 / 待改进

1. **模块完成度参差（已校准）**：`tf-counter` **已落地**（17 文件/1090 行：分桶+MQ削峰+批量落库+三级缓存）；
   `tf-hotspot` **半实现**（`LocalCacheWithBroadcast` + 广播失效已用，热/冷桶迁移未做）；
   `tf-benchmark` 才是**真空壳**（0 个 Java 文件，仅 .gitkeep + pom，parent pom 却声明它）。
   真正的割裂是 benchmark 空壳 + hotspot 半残，而非 counter 未做。
2. **无服务发现**：网关直连 `base-url`，无注册中心/客户端 LB（Nacos 仍是 Phase 2）。单实例/多实例扩容需手工改配置。
3. **Redis HA 支持但默认未启用（已校准）**：代码已有 `TURBOFEED_REDIS_MODE`(single/sentinel/cluster) + 哨兵/集群节点开关，
   `deploy/redis/docker-compose-cluster.yml`(3M3S) 也在；但当前默认 `mode:single`，prod yml 未切 cluster/sentinel——
   **修的是部署/配置，不是缺代码**。另：鉴权是 JWT 无状态，Redis 宕机**不挡登录**，但确实挡推荐流/时间线/社交图（这些是 Redis 权威源）。
4. **DLQ 兜底未断，缺部署侧告警通道（已校准）**：`DefaultDeadLetterAlert` 已带 Prometheus 指标
   `tf_outbox_deadletter_total{type}` + `OutboxDeadLetterSweeper` 清扫 + 死信表 + `FeedTimelineDlqService` 重放 + `DeadLetterAlert` 抽象。
   **代码层兜底没断**；缺的是把指标接成飞书/钉钉等真实告警通道，以及默认开启 replay（依赖 MQ）。这是运维/部署缺口，不是代码缺陷。

### 🔴 必须处理

- 无阻断性架构问题。

---

## 2. 代码实现评审

### ✅ 做得好的

1. **代码卫生干净**：全仓 TODO/FIXME 仅 6 处，无 `System.out`/`printStackTrace`，错误统一 `Result` + 全局异常处理。
2. **并发正确性有讲究**：画像衰减用 Lua 单线程原子执行（防并发读改写丢权重）；
   点赞热 key 按 64 槽打散（爆款不打单点）；计数冷启动用 Lua `EXISTS` 区分"key 不存在"与"值恰好相等"。
3. **fail-open 一致且可预期**：互动/画像/埋点挂了只告警不阻断浏览；资损链路（MQ）改 fail-fast 让事务回滚——
   "哪条该丢、哪条该抛"是有意识区分的，不是乱兜底。
4. **幂等与对账**：雪花 ID、`SADD`/`ZADD` 天然去重、`recountFromSlots`/`rebuildFromDb` 可对账重建。

### 🟡 风险 / 待改进

1. **上帝类**：`MediaReviewService` 1014 行，一个类同时承担审核状态机 + outbox 投递 + 信用分 + 处罚 + 健康分 + 机审策略——
   职责过载，建议按"状态机 / 信用处罚 / 投递"拆分。`MediaProperties`(814)、`ReviewProperties`(661) 配置类也偏大。
2. **catch 块数量大（367 处）**：虽绝大多数是有意 fail-open，但只有约 2/3 紧随其后有日志；
   需警惕"静默吞掉后既不告警也无指标"的路径。建议补一条静态规则：fail-open 必须有 warn 或 metrics，
   并把关键链路的丢弃/失败接到 Prometheus（已有 `OutboxBacklogMetrics` 是好榜样，应推广）。
3. **测试结构偏"验证脚本"而非单测**：73 个测试文件 + 大量 `target/verify/*.sh` 真 Redis 断言，
   端到端强但单元级回归网不够厚；fail-open 路径与边界值建议补纯单测。
4. **配置旋钮极多**：权重/半衰期/阈值全部 `@Value` 可配且都有默认值——灵活但也意味着配置漂移风险，
   建议对关键权重做配置基线测试（项目里已有"配错数字启动失败"的判别实验传统，值得沿用）。

### 🔴 必须处理

- 无阻断性代码缺陷。

---

## 3. 业务完整度评审

### ✅ 已形成的业务闭环

- **UGC 主链路完整**：手机号注册登录 → 一帖多图上传（预签名直传）→ 整帖 6 态审核状态机 → 公域 Feed 流 →
  互动（点赞/收藏/评论/分享/不感兴趣）→ 关注/粉丝关系 → 举报/申诉闭环。
- **内容安全体系**：机审策略 + 信用分级 + 处罚 + 账号健康分 + 热度复审（越火审得越严）。
- **推荐已具雏形**：多路召回（热点/兴趣标签/向量/流量池）+ 长短期双层画像 + session 序列 + 多目标排序。
- 收藏服务 `MediaFavoriteService` 已落地（与本次设计方案一致）。

### 🟡 业务缺口 / 待补

1. **推荐缺"度量闭环"**：没有 A/B 实验框架，规则模型 vs 模型化收益无法证伪；
   行为明细 `behavior-log` 默认关闭（训练数据没在沉淀）。这是推荐/ML 最大的前置阻塞。
2. **缺失常见社交能力**：无消息通知/互动通知、无私信/会话、无用户主页他页、无搜索历史/推送。
3. **无分析/运营后台数据**：OLAP（ClickHouse 蓝图）未建，运营看板、留存、漏斗分析无从谈起。
4. **冷启动依赖信用 L0 观察期**：新用户/新内容的冷启动策略已设计但效果未度量。
5. **无商业化**：无广告/分成/付费等（若是产品定位使然则非问题）。

### 🔴 必须处理

- 无阻断性业务缺陷。

---

## 4. 优先级建议（按投入产出排序）

| 优先级 | 事项 | 理由 |
|---|---|---|
| P0 | **打开 behavior-log + 建最简 A/B 分桶** | 推荐/ML 一切收益的前置；没有数据和度量，后续迭代都是拍脑袋 |
| P1 | **拆 `MediaReviewService` 上帝类**（1014 行/14 方法，审核+信用+处罚+健康+申诉全塞一起） | 职责过载，维护与回归风险随时间累积 |
| P1 | **Redis 切 cluster/sentinel（代码已支持，只改部署/配置）** | 权威源未启用高可用；改 yml/compose 即可，不必写代码 |
| P1 | **把 `tf_outbox_deadletter_total` 接成真实告警通道 + 默认开 replay** | DLQ 代码兜底已就绪，差最后一公里的运维接线 |
| P2 | **处置 `tf-benchmark` 空壳 + `tf-hotspot` 半残** | 要么补齐压测/热 key 迁移，要么从蓝图收缩，消除认知负担 |
| P2 | 通知/互动消息、运营分析（OLAP） | 体验与运营能力补齐 |
| P2 | 推荐模型化（i2i→DeepFM→双塔） | 等 P0 数据地基到位后，按既有 `RankModelClient`/`VectorRecallChannel` 缝插拔升级 |

> 校准说明（2026-10-29）：初版把 DLQ 兜底、tf-counter、Redis HA 三处判重——经代码复核：
> ① DLQ 已有 Prometheus 指标+清扫+重放服务，缺的是部署侧告警通道；
> ② tf-counter 已落地（17 文件/1090 行），不是骨架；
> ③ Redis HA 代码与 compose 都在，是"未启用"非"不支持"，且 JWT 无状态、Redis 挂不挡登录。
> 真正的硬缺口收敛为：**behavior-log 关 + 无 A/B（迭代无尺子）、MediaReviewService 上帝类、tf-benchmark 空壳+tf-hotspot 半残、Redis HA 未启用**。

---

## 5. 一句话总评

**架构上"想得很清楚"，代码上"克制且诚实"，业务上"主链路闭环、但度量与运营能力偏薄"。**
不需要推倒重来；下一步的关键不是再加功能，而是**把数据地基（埋点/A/B）和告警闭环补上**，否则推荐迭代会一直缺一把尺子。
