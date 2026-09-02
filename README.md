# turbo-feed

短视频 Feed 核心系统 —— 推拉结合 Feed 流 + 分布式计数服务 + 热点治理三件套，自带压测模块与可复现性能报告。

> 状态：微服务结构拆分完成（3 个独立可执行服务），引擎业务实现按迭代路线逐模块填充。

## 模块结构

```
turbo-feed/
├── tf-shared/         契约库：跨服务传输模型（Result / ErrorCode / DTO），零依赖
├── tf-hotspot/        热点治理 SDK（进程内库）：热 Key 探测 + 广播 + Caffeine L1，嵌入各引擎进程
├── tf-feed-engine/    Feed 引擎（独立服务 :8082）：收件箱(Redis List+LTRIM) / 大V outbox(ZSET) / 活跃度分层 / 多路归并
├── tf-counter/        计数服务（独立服务 :8081）：Redis 分桶 + MQ 削峰 + 批量落库 + 三级读缓存(L1/L2/L3)
├── tf-gateway/        HTTP 接入层（独立服务 :8080）：认证 + 媒体上传 + Sentinel 流量防护
└── tf-benchmark/      压测模块：五场景压测器 + Markdown 报告生成
```

服务间通信：同步走 HTTP、异步走 RocketMQ 事件（本地事件兜底），契约见 `tf-shared`。

## 技术栈

Java 17 · Spring Boot 3.4.x · Spring Cloud 2024.0.x · Maven 多模块 · Redis 7 + Redisson · Caffeine · RocketMQ（本地事件兜底） · H2 兜底 MySQL · Alibaba Sentinel

## 快速开始

```bash
mvn clean package
java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar          # 接入层  :8080
java -jar tf-counter/target/tf-counter-0.1.0-SNAPSHOT.jar          # 计数服务 :8081
java -jar tf-feed-engine/target/tf-feed-engine-0.1.0-SNAPSHOT.jar  # Feed引擎 :8082
```

## 技术文档

完整文档树见 [`docs/`](docs/README.md)（与代码同仓，单一文档来源）：

| 入口 | 内容 |
|---|---|
| [docs/architecture/overview.md](docs/architecture/overview.md) | 系统总体架构：模块 / 通信 / 版本锁定 / 关键决策 |
| [docs/modules/](docs/modules/) | 模块级技术文档（与 Maven 模块一一对应） |
| [docs/api/gateway-api.md](docs/api/gateway-api.md) | HTTP 接口：认证约定 / 参数 / curl / 错误码 |
| [docs/ops/deployment.md](docs/ops/deployment.md) | 构建部署 / 环境开关 / 生产前必改清单 |
| [docs/changelog/](docs/changelog/) | 变更记录（每次变更一份 md） |

维护约定：代码变更同一提交内同步模块文档，并追加编号 changelog。另附 `TurboFeed-详细设计.html`（上级目录 /Volumes/D/workbuddy/）。
