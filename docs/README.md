# turbo-feed 技术文档

> 本目录与代码同仓同构，是项目的**唯一技术文档来源**（Single Source of Truth）。
> 代码变更时必须同步更新对应文档，并按编号追加 [changelog](#5-变更记录)。

## 目录结构

```
docs/
├── README.md                 # 本文件：文档索引与维护约定
├── adr/                      # 架构决策记录（ADR）：做过且不该被随手推翻的决策
│   ├── README.md             # 索引 + 什么时候该写 ADR + 修改规则
│   ├── 0001-testing-strategy.md          # 引入自动化测试
│   ├── 0002-nacos-service-discovery.md   # 接入 Nacos 服务发现
│   ├── 0003-tf-counter-service.md        # 落地 tf-counter 计数服务
│   ├── 0004-hotspot-cache-sdk.md         # 抽 tf-hotspot 缓存失效 SDK
│   ├── 0005-contract-versioning.md       # 跨服务契约版本协商
│   ├── 0006-worker-id-lease.md           # 雪花 worker-id 自租约
│   └── 0007-observability.md             # 可观测性口径（指标 / 告警 / traceId）
├── architecture/             # 架构层文档
│   ├── overview.md           # 系统总体架构：模块 / 通信 / 技术栈 / 关键决策
│   ├── c4-model.md           # C4 模型：上下文 / 容器 / 组件三层（Mermaid 图）
│   ├── service-split.md      # 微服务拆分方案：部署单元 / 端口 / 通信矩阵
│   ├── sharding.md           # 分库分表：分片键选型 / 6 张逻辑表 / 演进与踩坑
│   ├── contract-evolution.md # 跨服务契约演进：版本协商 / 校验挂点 / 兼容性矩阵
│   ├── failure-mode-ledger.md# 全链路失败模式对账：投递语义 / 降级口径 / 指标与告警
│   ├── threat-model-degradation.md # 威胁模型与降级矩阵
│   ├── improvement-plan.md   # 评审问题诊断 + 改进落地追踪（M0~M6）
│   ├── moderation-design.md  # 审核体系设计蓝图（P0~P3，**规划中未实施**）
│   ├── content-security-design.md # 内容安全（文本）：五层架构 / 归一化 / 白名单（**S1 已落地，S2+ 规划**）
│   └── recommendation-design.md  # 推荐与用户画像：分表数据聚合分析 / CDC 出仓 / 召回排序（**规划中未实施**）
├── modules/                  # 模块级技术文档（与 Maven 模块一一对应，有 CI 检查）
│   ├── tf-gateway.md         # 网关：认证与 RBAC / 一帖多图上传 / 审核状态机 / 分片 / 配置
│   ├── tf-counter.md         # 计数服务：已落地（分桶 / MQ 削峰 / 批量落库 / 三级读缓存）+ 与 tf-hotspot 联动待补
│   ├── tf-feed-engine.md     # Feed 引擎：公域时间线读模型（已实现）+ 扇出蓝图
│   ├── tf-hotspot.md         # 热点探测 SDK：设计蓝图（尚未实现）
│   ├── tf-redis.md           # Redis 拓扑装配单一事实源：三态切换 / AutoConfiguration 机制
│   ├── tf-shared.md          # 公共契约：Result / ErrorCode / FeedItemView / FeedTimelineEvent
│   └── tf-benchmark.md       # 压测模块：五场景压测器 + Markdown 报告（**已实现**，不入服务拓扑）
├── api/                      # 接口文档
│   └── gateway-api.md        # HTTP 接口：全量端点 / 参数 / 返回结构 / 错误码 / curl
├── ops/                      # 运维层文档
│   └── deployment.md         # 构建部署 / 必需环境变量 / 配置开关 / 生产前必改清单
├── benchmark/                # 压测报告归档（tf-benchmark 实跑产出，按轮次）
│   ├── README.md             # 轮次索引 / 阶梯汇总 / 读法与诚实边界 / 复现命令
│   └── 2026-10-30-feed-*.md  # 第 1 轮 feed 场景五档阶梯（100/300/600/1200/2000 QPS）
└── changelog/                # 变更记录（每变更一份，编号递增；历史快照，不回头改）
```

> 目录结构与仓库的一致性由 `scripts/check-doc-drift.sh` 在 CI 中强制：
> 根 pom 里的每个 Maven 模块都必须有对应 `modules/*.md`，每份 ADR 都必须登记在 `adr/README.md`，
> 文档内的相对链接必须可达。

## 推荐阅读路径

| 角色 | 路径 |
|---|---|
| 新接手工程师 | [C4 架构模型](architecture/c4-model.md) → [架构总览](architecture/overview.md) → [tf-gateway](modules/tf-gateway.md) → [API](api/gateway-api.md) → 本地跑起来见 [部署](ops/deployment.md) |
| 前端 / 联调 | [API 文档](api/gateway-api.md)（含登录换取 JWT 与上传示例） |
| 运维 / 部署 | [部署文档](ops/deployment.md) → [服务拆分](architecture/service-split.md) → [压测基线](benchmark/README.md) |
| 线上排障 | [失败模式对账 §6](architecture/failure-mode-ledger.md)（**第一步永远先拿 traceId**） |
| 想知道「为什么这么设计」 | [ADR 索引](adr/README.md)（7 份，含否决过的方案与代价） |
| 审核 / 产品 | [审核体系蓝图](architecture/moderation-design.md)（P0~P3，**规划中**）→ [tf-gateway §4 审核状态机](modules/tf-gateway.md)（现状） |
| 内容安全 | [内容安全设计](architecture/content-security-design.md)（文本侧：五层架构 / 归一化抗绕过 / 白名单；**S1 已落地**） |
| 查历史变更 | [changelog 目录](changelog/)（倒序看编号） |

## 文档维护约定

1. **代码与文档同步**：改了类职责 / 接口签名 / 配置键，同一提交内更新对应模块文档；
2. **每变更一份 changelog**：编号 `NNNN-主题.md`，记录动机 / 改动 / 影响 / 验证 / 回滚；
3. **文档只写"为什么"和"契约"，不复述代码**：能从代码直接读出的细节不重复，长决策记录写在文档里；
4. 模块文档与 Maven 模块一一对应，新增模块必须新增对应 `modules/*.md`（**CI 会检查**）；
5. **架构决策写 ADR**：引入依赖 / 改契约 / 改故障口径 / 否决更简单的方案 → 新增
   [`adr/`](adr/README.md) 一份并更新索引。**已采纳的 ADR 不改结论**，要改就写新的一份覆盖它。

> 约定 4 与「文档内相对链接可达」由 [`scripts/check-doc-drift.sh`](../scripts/check-doc-drift.sh)
> 在 CI 中强制。它不是形式主义：一次 ADR 目录搬迁就造出过 7 处死链，
> 而 README 声称「模块文档一一对应」时 tf-redis / tf-benchmark 实际都缺文档。
