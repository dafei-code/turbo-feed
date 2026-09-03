# 0002 · tf-counter 独立成 Spring Boot 服务（8081）

- 日期：2026-08-28
- 类型：feat(counter)
- 关联方案：[服务拆分方案](../architecture/service-split.md)

## 动机

计数服务与接入层负载特征不同（MQ 削峰 + 批量落库的吞吐型负载），需独立伸缩与故障隔离。原为空骨架模块，本次升级为独立可执行服务。

## 改动清单

| 文件 | 改动 |
|---|---|
| `tf-counter/pom.xml` | 新增 `spring-boot-starter-web` / `spring-boot-starter-actuator`；新增 `spring-boot-maven-plugin` repackage（mainClass 指向 `CounterApplication`） |
| `tf-counter/src/main/java/com/turbofeed/counter/CounterApplication.java` | 新增主类，扫描范围限定 `com.turbofeed.counter` |
| `tf-counter/src/main/resources/application.yml` | 新增：应用名 `tf-counter`、端口 **8081**、H2 内存数据源、暂排除 Redisson 自动配置、actuator 仅暴露 health/info |

## 关键决策

- **Redisson 暂排除**：本地无 Redis，不排除则 starter 默认连 `localhost:6379` 启动即失败；Redis 就绪后删除 exclude 段并补 `spring.data.redis.*`（yml 内有注释标记）；
- **tf-hotspot 保持库依赖**：热点治理（L1 本地缓存 + 广播）天然嵌入进程，不拆独立服务。

## 验证

```bash
mvn -pl tf-counter -am package
java -jar tf-counter/target/tf-counter-0.1.0-SNAPSHOT.jar
curl http://localhost:8081/actuator/health   # {"status":"UP"}
```

## 回滚

删除主类与 yml、还原 pom（去掉 web / actuator / repackage 三段）。
