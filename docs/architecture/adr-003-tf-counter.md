# ADR-003：落地 tf-counter 计数服务（P1-2，提前到 P0 之后）

- **状态**：已采纳（Accepted，2026-10-17）
- **所属改进项**：`improvement-plan.md` §3 / P1-2（用户决策：提前到 P0 之后即做）
- **决策人**：dafei
- **关联 ADR**：ADR-001（测试）、ADR-002（Nacos，多实例计数依赖服务发现）

## 1. 背景（Context）

当前点赞 / 评论计数在 `tf-gateway` **直写 MySQL**（分片到 128 表）：`UPDATE media SET like_count = like_count + 1`。

- 高并发下**热点行锁竞争**（单条爆款内容的 like_count 行被大量 UPDATE 争抢）；
- 它同时是**推荐实时特征（热度分）的数据底座**——直写 MySQL 在压测下会先爆，拖慢排序；
- `tf-counter` 模块目前**只有空壳启动类**（`CounterApplication`），未实现。

用户决策：P0-1 测试 + P0-5 Nacos 是"敢上生产"两道坎，**P1-2 tf-counter 提前到 P0 之后就做**——它是推荐实时特征的数据底座，优先级实质上已到 P0。

## 2. 决策（Decision）

落地 `tf-counter`（Redis 分桶 + MQ 削峰 + 批量落库 + L1 Caffeine 本地缓存）：

1. **写入路径**：点赞 / 评论接口只写 Redis（按 `media_id` 取模分 64 桶 `INCR`），**不直写 MySQL**；经 MQ 削峰，后台批量 `UPDATE` 落库。
2. **读取路径**：排序模型读热度分优先 L1 Caffeine → Redis →（miss）MySQL；最终一致可接受（计数短暂偏差无碍推荐）。
3. **可靠性**：批量落库失败可重试；Redis 不可用 fail-open 回退读 MySQL（不阻断主流程）。
4. **热点治理**：64 桶打散单热点行，消除热点行锁；与 `tf-hotspot`（L1 + 广播失效）思路一致。
5. **计分接口**：`tf-counter` 暴露热度分查询接口，供 `tf-feed-engine` 排序模型消费（走 ADR-002 服务发现寻址）。

## 3. 后果（Consequences）

**正面**
- 点赞 / 评论 1000 QPS 下 MySQL 无热点行锁（看慢查询日志验证）；
- 热度分实时可用，推荐排序特征不再受写库拖累；
- 计数与写路径解耦，主流程更稳。

**负面 / 成本**
- 引入最终一致语义：计数短时间内可能与 MySQL 不一致（推荐可接受）；
- 多一跳依赖（Redis + MQ + tf-counter 服务），需消费端幂等（at-least-once）；
- 多实例部署依赖 ADR-002 Nacos 做服务发现。

## 4. 备选方案（Alternatives Considered）

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 纯 Redis 不落库 | **否决** | 重启 / 淘汰策略导致计数丢失，审计与推荐底座不可信 |
| 直写 MySQL + 乐观锁（version 字段） | **否决** | 热点行仍竞争，乐观锁重试放大写放大，压测先爆 |
| Redis Lua 原子 INCR + 定时落库（不分桶） | **折中但不足** | 单 key 仍热点，分桶才能打散；落库仍必要 → 采用本 ADR 的分桶 + MQ + 批量落库 |
| 数据库号段（segment）预分配计数 | **暂缓** | 适合"全局单调 ID"，不适合"按内容实时计数"，语义不符 |

## 5. 落地顺序

1. ADR-001（测试）完成 → 2. ADR-002（Nacos）完成 → 3. 本 ADR（tf-counter）：先 Redis 分桶写 + MQ 削峰 + 批量落库，再接排序模型热度分消费。
