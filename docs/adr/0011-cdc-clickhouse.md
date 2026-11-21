# ADR-0011：行为数据 CDC 出仓到 ClickHouse + 画像标签

- **状态**：提议（Proposed, 2026-10-31）
- **所属改进项**：推荐后续演进 #343
- **决策人**：dafei
- **关联 ADR**：行为日志 P1 归档 MinIO（已落地）、[item-CF 召回](../architecture/item-cf-recall-design.md)（消费 `user_interest`）、[0003 tf-counter](0003-tf-counter-service.md)

## 1. 背景（Context）

推荐系统（G1–G9 已落地）在线画像来源：Redis 实时特征（G5）+ `user_interest` / `media_tag` 表 + 内存 item 向量。离线侧缺乏：

- 用户长期行为宽表（埋点进 behavior_log 但只落盘 MinIO，未结构化）；
- 物品长期画像（完播率 / 互动率随时间长出）；
- 跨日特征与 A/B 报表。

这些用 OLTP（MySQL / ShardingSphere）扛不住分析查询（列存缺失、分片表 JOIN 灾难），且会压在线库。

**决策点**：用 CDC 把 OLTP 变更实时 / 近实时同步到 ClickHouse（列存、分析友好），与在线库隔离；画像标签 T+1 算好后回写在线可消费的 `user_interest` / 新 `user_tag` 表。

## 2. 决策（Decision）

1. **CDC 选型 Canal**：订阅 MySQL 主库（或只读副本）binlog → Canal Server → Kafka（topic 按表）→ 出仓作业。只读副本取 binlog，**不压主库在线读写**。
2. **与 OLTP 隔离**：Canal 接已建的 3307 只读副本；CDC 链路故障不影响在线；在线写照常走主库。
3. **ClickHouse 表（按主题）**：
   - `dwd_user_action`（明细，分区 by `date`）：用户行为（含 behavior_log 回流的 WATCH / LIKE / COMMENT / SHARE / FOLLOW）；
   - `dim_user_profile`（用户画像标签宽表，按 `user_id`）；
   - `dim_item_profile`（物品画像：完播率 / 互动率 / 热度，按 `item_id`）；
   - `agg_content_daily`（内容日聚合，供运营 / A/B 报表）。
4. **画像标签计算**：Flink / Spark 近实时 / 离线算 `dim_user_profile`（兴趣向量、活跃度、冷启动标记）与 `dim_item_profile`；**回写在线**：将 `user_interest` 风格标签写回 MySQL `user_interest`（item-CF 已消费）或新增 `user_tag` 表，供在线召回 / 精排特征。
5. **降级语义**：CH 不可用时，在线继续用现有 Redis / MySQL 画像（已有 fail-open）；分析报表降级为「最近一次成功产出」；CDC 堆积在 Kafka 不丢（至少一次，按 `event_id` 幂等去重）。
6. **位点与幂等**：Canal 位点持久化；出仓按 `event_id` / 主键 upsert，重复 binlog 幂等。

## 3. 后果（Consequences）

**正面**

- 离线特征闭环，推荐可吃长期信号（兴趣漂移、物品长周期热度）；
- 运营 / A/B 有真报表（不再靠行为日志手扒）；
- 与在线库解耦，分析查询不压主库。

**负面 / 成本**

- **新增三套基建**：Canal + Kafka + ClickHouse（部署 / 运维成本，docker-compose opt-in）；
- **Schema 漂移风险**：binlog 字段变更要同步 CH 表，需契约校验（沿用 `scripts/check-doc-drift` 思路，对 binlog schema ↔ CH 列做 CI 强校验）；
- **回写在线限流**：画像回写 MySQL 必须限流，避免冲在线库（批量 + 限速 + 低峰）；
- **链路长、排障难**：binlog → Kafka → 出仓 → CH → 回写，任一环抖动都影响画像时效，需端到端监控与位点告警。

## 4. 备选方案

| 方案 | 否决理由 |
|---|---|
| 直连 MySQL 跑分析 SQL | 压在线库、无列存优势、分片表 JOIN 灾难 |
| Flink CDC 直写 CH（不要 Canal） | 也可行；但 Canal + Kafka 解耦更利于多消费者（画像 / 报表 / 特征），本期选 Canal 因运维简单、与 behavior-log 经验一致 |
| 画像只在 CH 算、不回写在线 | item-CF / 精排拿不到长期特征，推荐收益减半，否决 |

## 5. 落地记录（待做，纯设计）

- 部署 Canal + Kafka + ClickHouse（docker-compose，opt-in）。
- 出仓作业：binlog → `dwd_user_action`（含 behavior_log 回流）；`dim_user_profile` / `dim_item_profile` 计算 + 回写 `user_interest` / `user_tag`。
- 在线侧：推荐特征服务接 `user_tag`（增量），A/B 报表接 CH。
- 契约校验：binlog schema ↔ CH 列映射 CI 强校验。

## 6. 状态核查（2026-11-20）

**仍为"提议"是正确的，不改成已采纳**：源码核对结果为**零落地**——全部 `pom.xml` 无 canal / kafka-clients / clickhouse 依赖，
无 Canal 部署配置，无出仓作业。`behavior_log` 归档到 MinIO（已落地）是独立链路，不是本 ADR 的 binlog→Kafka→CH 出仓。
另一条线 `/Volumes/D/workbuddy/用户画像落地方案-01~04-知识星球.md` 是**规划文档**（开头已声明未实施），同样不是落地证据。

**规划细化 ≠ 决议**：画像落地方案 4 篇比本 ADR 更细，但属于本 ADR 的施工级展开，不改变本 ADR 的 Proposed 状态。

**待决策**（本次收口未替决策人拍板，保持 Proposed）：

| 选项 | 含义 | 触发条件 |
|---|---|---|
| 排期实施 | 按用户画像落地方案 M1→M4 分期推进 | 需要长期兴趣 / 留存 / 完播分析，且能为在线推荐带来可测收益 |
| 明确不做（Deprecated） | 承认列存数仓不在本期范围，状态改"已废弃" | 若判断当前 Redis 实时画像 + MinIO 归档已够用 |

注意与 [ADR-0016](0016-distributed-transaction-redesign.md) 的约束冲突：0016 明确"不引入新中间件"，
若选排期实施，需在 ADR 中显式标注本决策**推翻 0016 的该条约束**并给出理由，不能两边都写成有效决策。
