# 服务拆分方案（Service Split Plan）

> 状态：2026-08-28 已落地结构拆分；引擎业务实现按迭代路线逐模块填充。
> 变更记录：见 [docs/changelog/](../changelog/)，每次变更一份 md。

## 1. 背景与动机

拆分前 tf-gateway 以 Maven 依赖方式内嵌 tf-counter / tf-feed-engine / tf-hotspot，单进程运行。问题：

1. **传递依赖污染**：gateway 进程被迫加载 Redisson / JDBC 等引擎专属重依赖（已踩过 Redisson 自动配置导致启动失败的坑，临时用 `<exclusion>` 压制）；
2. **无法独立伸缩**：计数与 Feed 扇出的负载特征完全不同，单进程无法按需扩容；
3. **故障域不分**：引擎 OOM / GC 停顿会拖垮 HTTP 接入层；
4. **与目标架构不符**：工业级形态应为"接入层 + 三大引擎各自独立部署单元"。

## 2. 拆分原则

- **部署单元 vs 进程内库**：有独立伸缩 / 故障隔离诉求的才拆成服务；天然嵌入进程的（热点治理 L1 本地缓存 + 广播）保持库形态。
- **契约归 tf-shared**：跨服务传输的模型（Result / ErrorCode / 事件 DTO）只放 tf-shared，保持零依赖。
- **克隆即跑**：无 Redis / MQ 的环境也能启动全部服务（自动配置条件化 / 暂排除，就绪后移除）。

## 3. 拆分结果

| 模块 | 形态 | 端口 | 职责 | 关键依赖 |
|---|---|---|---|---|
| tf-gateway | 独立服务（可执行 jar） | 8080 | HTTP 接入：认证 + 媒体上传 + Sentinel 流量防护 | web / sentinel / rocketmq / actuator |
| tf-counter | 独立服务（可执行 jar） | **8081** | 分布式计数：Redis 分桶 + MQ 削峰 + 批量落库 + 三级缓存 | redisson / jdbc / h2 / caffeine |
| tf-feed-engine | 独立服务（可执行 jar） | **8082** | Feed 推拉核心：收件箱 / outbox / 活跃度分层 / 多路归并 | redisson / caffeine |
| tf-hotspot | **进程内 SDK（库）** | - | 热 Key 探测 + 广播 + 本地 L1 缓存，嵌入 counter / feed-engine 进程 | caffeine |
| tf-shared | 契约库 | - | Result / ErrorCode / 跨服务 DTO | 零依赖 |
| tf-benchmark | 压测模块 | - | 压测器 + 报告生成 | 独立 |

## 4. 通信矩阵

| 调用方 → 提供方 | 方式 | 落地状态 |
|---|---|---|
| gateway → counter / feed-engine | 同步 HTTP（Spring Cloud LoadBalancer + RestClient） | 契约已定，引擎出接口时引入客户端 |
| gateway → 引擎（事件类） | 异步 RocketMQ（`turbofeed.mq.enabled` 条件切换，本地事件兜底） | 事件总线已在 gateway 落地 |
| 引擎 → 引擎 | 异步事件为主，避免同步链路放大故障 | 按业务出现时定 |

**服务发现**：当前直连 `host:port`；Phase 2 引入 Nacos（Spring Cloud Alibaba 已在 BOM 链中对齐）。

## 5. 环境约束处理（克隆即跑）

- **Redis 暂缺**：tf-counter / tf-feed-engine 的 `application.yml` 暂时排除 `RedissonAutoConfiguration`（含注释），Redis 就绪后删除该段并补 `spring.data.redis.*`；
- **MQ 暂缺**：RocketMQ 发布器 / 消费者由 `turbofeed.mq.enabled=true` 才激活，默认本地 Spring 事件；
- **DB 暂缺**：tf-counter 用 H2 内存库兜底，生产切 MySQL。

## 6. 验证方式

```bash
mvn clean package
java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar       # 8080
java -jar tf-counter/target/tf-counter-0.1.0-SNAPSHOT.jar       # 8081
java -jar tf-feed-engine/target/tf-feed-engine-0.1.0-SNAPSHOT.jar  # 8082

curl http://localhost:8080/actuator/health   # UP
curl http://localhost:8081/actuator/health   # UP
curl http://localhost:8082/actuator/health   # UP
```

## 7. 演进路线

| 阶段 | 内容 |
|---|---|
| 已完成 | 结构拆分：3 个独立可执行服务 + 端口规划 + actuator 健康检查 |
| 下一步 | 引擎业务实现填充；gateway 引 HTTP 客户端调引擎；traceId 全链路日志（Phase 1 方案已定） |
| Phase 2 | Nacos 服务发现 + Prometheus / Grafana + Loki 日志集中 |
| Phase 3 | 多机房容灾与降级预案 |
