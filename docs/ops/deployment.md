# 构建与部署

## 1. 构建

```bash
mvn clean package            # 全模块构建 + repackage 可执行 jar
mvn -pl tf-gateway -am package   # 只构建网关（-am 连带 tf-shared）
```

环境要求：**JDK 17**（与 `maven.compiler.release=17` 一致；IDEA 项目 SDK 也必须为 17，切 JDK 24 会触发 Lombok 注解处理器崩溃）。

## 2. 启动（克隆即跑，零外部依赖）

三个服务相互独立、无启动顺序依赖（引擎业务未实现前无真实调用）：

```bash
java -jar tf-gateway/target/tf-gateway-0.1.0-SNAPSHOT.jar      # :8080
java -jar tf-counter/target/tf-counter-0.1.0-SNAPSHOT.jar      # :8081
java -jar tf-feed-engine/target/tf-feed-engine-0.1.0-SNAPSHOT.jar  # :8082
```

验收：`curl localhost:808x/actuator/health` 全部 `{"status":"UP"}`；网关登录 + 上传按 [API 文档](../api/gateway-api.md) 走通。

## 3. 环境开关（当前默认 = 本地零依赖模式）

| 开关 | 当前值 | 打开后 |
|---|---|---|
| `turbofeed.mq.enabled` | false | RocketMQ 跨进程事件（需放开 `rocketmq.name-server`） |
| `turbofeed.media.processing-enabled` | false | ImageIO 缩略图处理链（注意解码内存 ≈ 48MB/张） |
| 引擎服务 Redisson autoconfigure exclude | 生效 | Redis 就绪后删除 exclude，补 `spring.data.redis.*` |
| H2 数据源 | 内存库 | 生产切 MySQL 8（`MODE=MySQL` 已对齐语法） |

## 4. 生产前必改清单

- [ ] `turbofeed.jwt.secret`：替换为密钥管理注入的高熵值
- [ ] `turbofeed.auth.demo-users`：替换为数据库账号 + 哈希（BCrypt/Argon2）校验
- [ ] `turbofeed.media.public-url-base`：指向真实对象存储（MinIO / COS）
- [ ] H2 → MySQL；PlaceholderStorageClient → MinioStorageClient
- [ ] 机审接入内容安全 API（MediaReviewService TODO 占位处）

## 5. 部署演进

| 阶段 | 形态 |
|---|---|
| 当前 | 单机三个 jar，actuator 探活 |
| Phase 2 | K8s 部署（health 探针 + Prometheus 指标），Promtail + Loki 集中日志，Micrometer Tracing |
| Phase 3 | 多机房容灾 + 容量规划（tf-benchmark 压测报告驱动） |
