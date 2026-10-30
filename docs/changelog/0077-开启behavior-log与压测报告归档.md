# 0077 · L5 收尾：开启 behavior-log + 压测报告归档

- **状态**：已完成（开关本机实跑验证 + 编排默认值调整；镜像内未验证，Docker 未启动）
- **优先级**：上线前置项（评审文档认定的 L4→L5 最后两件「非代码」事项）
- **关联**：0057（behavior-log M0 落地）、0075（tf-benchmark）、0076（部署链路）

## 为什么做

`docs/architecture/project-review-2026-10.md` 把「升级到生产级 L5」收束成两件非代码的事：

1. **打开 behavior-log 让数据开始攒**——代码就绪但开关默认关，训练样本一条没有；
2. **用 tf-benchmark 真跑一轮并留下结果**——工具造好了但没有实测数字。

本次把这两件都兑现，并顺手纠正了一个**我自己写错的运维结论**。

## 改动

### 1. 开启 behavior-log

| 位置 | 改动 | 理由 |
| --- | --- | --- |
| 本机 `application.yml`（gitignored） | `turbofeed.feed.behavior-log.enabled: true`，`dir: logs/behavior` | 本机起服务即攒样本，不必每次 export；`logs/` 已在 `.gitignore` |
| `docker-compose.yml`（开发编排） | 默认值 `false` → `true` | 与 prod 编排一致，避免「换编排方式就静默不采」 |
| `deploy/compose/docker-compose.prod.yml` | 原本已是 `true` | — |
| `deploy/env/.env.example` | 原本已是 `true` | — |

**代码里的默认值仍是 `false`，没有改**。默认值按「谁承担后果」分层：
配置骨架的 `false` 保护「任何人的机器都不该被静默写盘」；部署编排的 `true` 是运维显式声明的环境，
攒样本正是它存在的理由。**默认不写，部署要写**。

### 2. 压测报告归档到 `docs/benchmark/`

`tf-benchmark` 把报告写到 `target/benchmark/`（gitignored），**不归档的跑分等于没跑**。
新增 `docs/benchmark/`：5 份原始报告 + `README.md`（阶梯汇总 / 读法 / 诚实边界 / 复现命令）。

### 3. 纠正「变量名必须带 FEED」的归因（重要）

原文档（`behavior-data-pipeline.md` / 两份 compose / `.env.example` / `preflight.sh`）都写：

> 写成 `TURBOFEED_BEHAVIOR_LOG_ENABLED` 时 Spring **不会报错**，只会静默读到默认值 `false`

**实测证伪**：直接 `java -cp ...` 起引擎、只设 `TURBOFEED_BEHAVIOR_LOG_ENABLED=true`，
启动日志照样打印「行为明细落盘已启用」。原因是 `application.yml` 里该属性本身就写成
`${TURBOFEED_BEHAVIOR_LOG_ENABLED:false}`——**显式占位符压过了松散绑定的命名规则**。

真正的失效原因是**另一层**：compose 只把 `TURBOFEED_FEED_BEHAVIOR_LOG_ENABLED` 这一个名字
转发进容器，不带 FEED 的变量**根本进不去**，于是静默按默认值走。

结论不变（部署仍必须带 FEED），但**归因改了**：不是 Spring 不认，是编排没转发。
`preflight.sh` 的阻断保留——它校验的正是 `.env`→容器这条链路，这条链路上判断是对的。
文档与注释全部改成准确表述，并补了「怎么自证」：启动日志第一屏看
`行为明细落盘已启用` / `行为明细落盘已关闭`。

## 验证证据（本机真跑，非推断）

| 验证项 | 方法 | 结果 |
| --- | --- | --- |
| 部署链路的开关生效 | `TURBOFEED_FEED_BEHAVIOR_LOG_ENABLED=true` 起引擎 | 日志：`行为明细落盘已启用: dir=logs/behavior, queueCapacity=10000, flushIntervalMillis=1000` |
| 本机 yml 开关生效 | **不带任何环境变量**起引擎 | 同上，说明 `application.yml` 的 `true` 生效 |
| 非 FEED 变量（对照） | 只设 `TURBOFEED_BEHAVIOR_LOG_ENABLED=true` | 同样「已启用」，证实 §3 的归因纠正 |
| 落盘正确性 | `POST /internal/feed/behavior` 推 30 条 `IMPRESSION` | `feed-behavior-20261030.jsonl` **30 行**，逐行 JSON 可解析，8 个字段顺序固定 |
| **Redis 不可用时不丢样本** | 全程未启动 Redis | 统计写入逐条 `WARN ... Unable to connect to Redis`（fail-open），30 条样本**一条不少**落盘 |
| 跨重启追加写 | 重启引擎再推 5 条 | 30 → **35 行**，未截断、未覆盖 |
| 编排/配置可解析 | ruby `YAML.load_file` × 3 + `bash -n preflight.sh` | 全部通过；`preflight.sh .env.example` 第 5 项显示「已开启」 |

验证产生的 35 条 `verify_*` 假样本已删除，`logs/behavior/` 恢复为空目录（避免污染真实训练集）。

## 影响与代价

- **磁盘**：`logs/behavior/feed-behavior-YYYYMMDD.jsonl` 按天分文件，**没有清理策略**，
  长期运行会线性增长。这是 `behavior-data-pipeline.md` §7 已登记的风险，P1 会连带做保留期。
- **主链路开销**：未在本轮压测中量化（压测时开关是关的）。JSON 序列化 + 有界队列 + 单线程落盘，
  队列满即丢，不阻塞请求；但要拿到「开/关的吞吐差异」需要重跑一轮对照压测。

## 未做（明确留白）

- behavior-log 采集链路 P1（定时压缩上传 MinIO）、`tf_behavior_log_dropped_total` 指标
- 打开开关后的同条件压测对照
- 镜像内验证（本机 Docker 未启动）
