# 0003 · tf-feed-engine 独立成 Spring Boot 服务（8082）

- 日期：2026-08-28
- 类型：feat(feed-engine)
- 关联方案：[服务拆分方案](../architecture/service-split.md)

## 动机

Feed 推拉结合引擎（收件箱 / 大 V outbox / 扇出 Worker）是重 Redis 读写型负载，扇出高峰与接入层 QPS 高峰不同步，需独立伸缩。原为空骨架模块，本次升级为独立可执行服务。

## 改动清单

| 文件 | 改动 |
|---|---|
| `tf-feed-engine/pom.xml` | 新增 `spring-boot-starter-web` / `spring-boot-starter-actuator`；新增 `spring-boot-maven-plugin` repackage（mainClass 指向 `FeedEngineApplication`） |
| `tf-feed-engine/src/main/java/com/turbofeed/feedengine/FeedEngineApplication.java` | 新增主类，扫描范围限定 `com.turbofeed.feedengine` |
| `tf-feed-engine/src/main/resources/application.yml` | 新增：应用名 `tf-feed-engine`、端口 **8082**、暂排除 Redisson 自动配置、actuator 仅暴露 health/info |

## 关键决策

- 与 tf-counter 同型的 Redisson 暂排除策略（Redis 就绪后删除，yml 内有注释标记）；
- 引擎间通信以异步事件为主，避免同步链路放大故障（见拆分方案通信矩阵）。

## 验证

```bash
mvn -pl tf-feed-engine -am package
java -jar tf-feed-engine/target/tf-feed-engine-0.1.0-SNAPSHOT.jar
curl http://localhost:8082/actuator/health   # {"status":"UP"}
```

## 回滚

删除主类与 yml、还原 pom（去掉 web / actuator / repackage 三段）。
