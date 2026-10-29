# 行为数据链路（behavior-log）设计与存储方案

> **状态：部分实施。**
> 已实施：明细落盘 + 容器持久卷 + 部署接线（`deploy/compose/docker-compose.prod.yml`）。
> **未实施**：采集导出到对象存储/数仓、备份与清理策略、实时链路。本文的 P1/P2/P3 属规划。
>
> 适用读者：要打开 `behavior-log`、或要基于这些明细做训练样本/看板的人。

---

## 1. 它解决什么问题

在此之前，行为事件只用于累加 Redis 计数器和兴趣画像，**聚合完即丢弃**。计数能回答
"这条内容有多少赞"，但回答不了"哪个用户在什么位置看到了什么、点了没点"——后者才是训练样本。

`BehaviorLogSink`（`tf-feed-engine/.../logging/`）就是为这件事落的**原始明细**，是双塔召回、
精排、特征存储、粗排截断四项的前置依赖。

注意它与网关侧 `interaction_event` 表**不是一回事**：

| | `behavior-log`（本文） | `interaction_event`（MySQL） |
| --- | --- | --- |
| 位置 | 引擎进程 → JSONL 文件 | 网关进程 → MySQL 分片表 |
| 内容 | 曝光/播放/滑动手势等**全量高频**事件 | 点赞/收藏/评论等**互动**事件 |
| 定位 | 训练样本原始层（ODS） | 业务事件流水 |
| 写入 | 异步批量，队列满即丢 | 同步事务 |
| 消费者 | 离线训练任务 | `TwoTowerUserTrainer` 等 |

两者互补：`interaction_event` 有事务保证但只有互动；`behavior-log` 覆盖全量但允许丢样本。

---

## 2. 当前实现（已落地）

| 项 | 实现 |
| --- | --- |
| 写入方式 | 单工作线程 + 有界 `ArrayBlockingQueue`，队列满**直接丢弃并计数**（`dropped()`） |
| 文件格式 | JSONL，一行一个 `FeedBehaviorEvent` |
| 文件切分 | 按天：`feed-behavior-YYYYMMDD.jsonl` |
| 目录 | `turbofeed.feed.behavior-log.dir`，默认 `logs/behavior`，容器内为 `/app/logs/behavior` |
| 开关 | `turbofeed.feed.behavior-log.enabled`，**默认 false** |
| 持久化 | 生产编排挂命名卷 `behavior-logs:/app/logs` |
| 故障语义 | 全链路 fail-open；埋点挂掉不影响统计与画像（两者各自独立写入） |

**为什么默认关闭**：落盘是"为了攒数据"的行为，不该在任何人的开发机上静默写文件。需要采集时显式打开。

**"队列满即丢"是设计而非缺陷**：丢埋点损失的是样本量，卡住请求损失的是可用性。

---

## 3. 标准方案：五层链路

```
① 采集   客户端 SDK ──批量/压缩/失败重试/离线补传──▶ 日志网关（鉴权·限流·schema 校验·脱敏·打标）
② 传输   Kafka / Pulsar     topic 按事件类型拆，分区键=userId，保留 3~7 天（可重放）
③ 落地   双链路
           离线：Kafka ─▶ 每 5~15min 落 HDFS/S3 ─▶ 转 Parquet ─▶ Hive 外表（dt= 分区）
           实时：Kafka ─▶ Flink ─┬─▶ ClickHouse / Doris / StarRocks（看板、明细检索）
                                 └─▶ Redis / HBase（在线特征）
④ 建模   ODS(原始) ─▶ DWD(清洗去重脱敏·宽表) ─▶ DWS(日粒度汇总) ─▶ ADS(应用层/样本集)
⑤ 服务   训练读 ADS 样本集；在线 serving 读 Redis 特征；运营看板查 ClickHouse
```

### 3.1 各层选型

| 载体 | 格式 | 擅长 | 不适合 | 位置 |
| --- | --- | --- | --- | --- |
| Kafka / Pulsar | 二进制 + Schema Registry | 削峰、重放、多消费方解耦 | 长期存储、条件查询 | 传输缓冲 |
| 对象存储（S3/HDFS） | **Parquet / ORC**（列式） | 海量低成本、只扫需要的列、压缩比 5~10x | 点查、实时更新 | 离线地基 |
| ClickHouse / Doris | 列存 + 稀疏索引 | 实时聚合、明细检索 | 高频点更新、超高并发点查 | 实时分析 |
| Hive / Spark | Parquet 外表 | 批量 ETL、样本生成 | 低延迟 | 离线计算 |
| MySQL | 行存 | 事务、点查 | 海量明细（行数一上来就崩） | 业务库 |
| Redis / HBase | KV | 在线特征毫秒读 | 任何分析查询 | 在线特征 |

### 3.2 四条决定成败的规则

| 规则 | 做法 | 不做的后果 |
| --- | --- | --- |
| **只追加不可变** | 日志永不 update，修正靠重算分区 | 无法回溯、无法审计、并发写冲突 |
| **按 dt 物理分区** | `dt=2026-10-29/hh=13/` 目录即分区 | 补一天数据要全量重跑；查询扫全表 |
| **幂等 event_id** | 推荐曝光去重 | 同一用户重复曝光把样本刷成"越推越推" |
| **Schema 演进** | Avro/Protobuf + Registry，或 Parquet 只加可选列 | 加一个字段就让所有下游解析失败 |

---

## 4. 差距：当前 vs 标准

| 环节 | 标准方案 | 当前实现 | 差距性质 |
| --- | --- | --- | --- |
| 采集 | 客户端 SDK + 日志网关 | 引擎进程内直接记 | **没有客户端曝光/滑动手势埋点**，只有服务端可见事件 |
| 传输 | Kafka 可重放 | 进程内队列，满即丢 | 进程崩溃即丢；**不可重放** |
| 落盘 | S3 Parquet 分区 | 本地 JSONL，按天 | 已挂卷（容器重建不丢），但**无副本、无分区、未压缩** |
| 实时 | Flink → ClickHouse | 无 | 无实时看板/实时特征 |
| 离线 | Hive/Spark 读 Parquet | 脚本直接读 JSONL | 量小够用，大了必崩 |
| 建模 | ODS/DWD/DWS/ADS | 无分层 | 脏数据直接进训练 |
| 备份 | 定时快照 + 恢复演练 | 无 | 卷所在磁盘坏了就没了 |

**公允地说**：当前形态是 M0 阶段的合理取舍——先保证明细不丢，导入通道以后换也不影响上游。
它只是还没走到标准形态，不是设计错误。

---

## 5. 演进路径

| 阶段 | 动作 | 成本 | 解决什么 | 前置依赖 |
| --- | --- | --- | --- | --- |
| **P0（已完成）** | 服务容器挂 `behavior-logs` 命名卷；开关 + 目录经环境变量注入 | 已完成 | 容器重建/换镜像不丢数据 | 无 |
| **P1** | 引擎侧定时（每小时）把当日 JSONL 压缩后上传 MinIO：`behavior/dt=YYYY-MM-DD/hh=HH/*.jsonl.gz` | 2~3 天 | 持久性（多副本）、按天可回溯、可被离线任务直接读 | MinIO（已部署） |
| **P2** | 离线任务把 JSONL 转 **Parquet**，建 Hive/Spark 外表；`clickhouse-local` 也可直接查 | 1~2 天 | 列式压缩、扫描快、能接 BI | P1 |
| **P3** | 单机 **ClickHouse** 承接看板查询 | 1 天 | 运营看板、漏斗、留存 | P2 |
| **P4** | Kafka + Flink | 大 | 实时特征、秒级看板 | 日增千万级才值得 |

**不建议现在做的**：Kafka/Flink 在单机上纯属运维负担。日增不到千万级时，MinIO + Parquet 完全够用。

---

## 6. 运维手册

### 6.1 打开开关

```bash
# deploy/env/.env 中
TURBOFEED_FEED_BEHAVIOR_LOG_ENABLED=true
```

> ⚠️ **变量名必须带 `FEED`**。属性是 `turbofeed.feed.behavior-log.enabled`，
> 写成 `TURBOFEED_BEHAVIOR_LOG_ENABLED` 时 Spring **不会报错**，只会静默读到默认值 `false`——
> 表现为「环境变量设了、日志没异常、数据一条没有」。`deploy/scripts/preflight.sh` 会拦这个错误。

### 6.2 确认真的在写

```bash
docker compose -f deploy/compose/docker-compose.prod.yml exec tf-feed-engine \
  sh -c 'ls -lh /app/logs/behavior/ && tail -1 /app/logs/behavior/*.jsonl'
```

启动日志里应有 `行为明细落盘已启用`；关闭时是 `行为明细落盘已关闭（...=false）：不写文件`。

### 6.3 容量估算

单条约 300 字节 JSON：

| 日事件量 | 未压缩 | gzip 后 | 月增量（压缩） |
| --- | --- | --- | --- |
| 10 万 | 30 MB | ~6 MB | ~180 MB |
| 100 万 | 300 MB | ~60 MB | ~1.8 GB |
| 1000 万 | 3 GB | ~600 MB | ~18 GB |

**当前没有任何清理策略**——这是已知缺口。P1 落地前，磁盘占用随运行时长线性增长，需要人工巡检：

```bash
du -sh /var/lib/docker/volumes/turbo-feed_behavior-logs/_data
```

### 6.4 导出与备份（P1 落地前的过渡手段）

```bash
# 把卷内容导出到宿主
docker run --rm -v turbo-feed_behavior-logs:/data -v "$PWD":/out alpine \
  tar czf /out/behavior-$(date +%F).tar.gz -C /data .
```

---

## 7. 未决风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 无采集链路 | 数据只在单机卷里，磁盘故障即全失 | P1；过渡期人工导出到 MinIO |
| 无清理策略 | 磁盘线性增长，最终写满导致容器异常 | 人工巡检 + P1 顺带做保留期 |
| 无客户端埋点 | 曝光未真正展示、滑动手势等事件拿不到，训练样本偏向"服务端已下发" | 需客户端 SDK，属独立工作项 |
| 队列满丢弃不可见 | `dropped()` 只是内存计数，未接 Prometheus | 建议补 `tf_behavior_log_dropped_total` 指标 |
| JSONL 无 schema 校验 | 字段漂移只能靠下游解析失败发现 | P2 转 Parquet 时引入显式 schema |

---

## 8. 相关文件

| 文件 | 作用 |
| --- | --- |
| `tf-feed-engine/.../logging/BehaviorLogSink.java` | 落盘实现 |
| `tf-shared/.../model/FeedBehaviorEvent.java` | 事件契约（字段即协议） |
| `deploy/compose/docker-compose.prod.yml` | 生产编排：`behavior-logs` 卷 + 开关注入 |
| `deploy/env/.env.example` | 环境变量清单（含变量名陷阱说明） |
| `deploy/scripts/preflight.sh` | 启动前校验（含错误变量名检测） |
| `docker-compose.yml` | 开发编排：同样挂卷，默认不开 |
