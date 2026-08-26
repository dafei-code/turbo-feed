# turbo-feed

短视频 Feed 核心系统 —— 推拉结合 Feed 流 + 分布式计数服务 + 热点治理三件套，自带压测模块与可复现性能报告。

> 状态：工程骨架已搭好（仅结构与构建配置），按迭代路线逐模块填充实现。

## 模块结构

```
turbo-feed/
├── tf-shared/         公共模型与 SPI：User / FeedItem / LikeEvent / MqProvider / InboxStore / CounterStore
├── tf-hotspot/        热点探测与治理：滑动窗口热 Key 探测 + 广播 + 热/冷桶迁移 + Caffeine L1
├── tf-feed-engine/    Feed 推拉核心：收件箱(Redis List+LTRIM) / 大V outbox(ZSET) / 活跃度分层 / 多路归并
├── tf-counter/        计数服务：Redis 分桶 + MQ 削峰 + 批量落库 + 三级读缓存(L1/L2/L3)
├── tf-gateway/        HTTP 接入层(8080)：聚合三大引擎 + Sentinel 流量防护（可执行 jar）
└── tf-benchmark/      压测模块：五场景压测器 + Markdown 报告生成
```

## 技术栈

Java 17 · Spring Boot 3.4.x · Maven 多模块 · Redis 7 + Redisson · Caffeine · SPI 可插拔 MQ（内存 MQ 默认 / RocketMQ 适配器） · H2 兜底 MySQL · Alibaba Sentinel

## 快速开始（占位，待实现后补全）

```bash
mvn clean package
java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar
```

## 设计文档

详细设计见 `TurboFeed-详细设计.html`（同目录上级 /Volumes/D/workbuddy/）。
