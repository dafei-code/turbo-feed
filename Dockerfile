# =============================================================================
# turbo-feed 多阶段构建镜像（参数化：以 MODULE 指定要打包的 Maven 子模块）
#
# 用法（仓库根目录执行）：
#   docker build -f Dockerfile --build-arg MODULE=tf-gateway     -t turbo-feed-gateway .
#   docker build -f Dockerfile --build-arg MODULE=tf-feed-engine -t turbo-feed-engine .
#   docker build -f Dockerfile --build-arg MODULE=tf-counter     -t turbo-feed-counter .
# 也可由 docker-compose.yml / deploy/compose/docker-compose.prod.yml 直接 build（已配 build.args.MODULE）。
#
# ── 与初版相比改了什么（2026-10-29）───────────────────────────────────────────
#   1. **分层缓存**：先只 COPY 各模块 pom.xml 跑依赖预取，再 COPY 源码。
#      初版 `COPY . .` 在 `mvn package` 之前，改一行 Java 就让上一层全部失效，
#      每次构建都要重新下载全部依赖（纯网络等待占大头）。
#   2. **非 root 运行**：初版 `USER root` 并注释「生产建议改」，这里直接落到镜像。
#      JVM 进程被打穿时（反序列化 / 注入类漏洞）能拿到的权限越低越好。
#   3. **JVM 容器参数**：用 JDK_JAVA_OPTIONS（Java 9+ 原生支持，无需 sh -c 包裹）。
#      关键是 MaxRAMPercentage——不设时 JVM 按宿主机内存算堆，容器限额更小时会被 OOMKilled。
#   4. **镜像级 HEALTHCHECK**：初版装了 curl 却没声明 HEALTHCHECK，裸 `docker run` 时
#      编排层无法判断容器是否真的可用（compose 里的 healthcheck 只覆盖 compose 场景）。
#   5. **日志目录预建并授权**：behavior-log 要往 /app/logs 写 JSONL，非 root 用户必须可写；
#      预建还能让 named volume 首次挂载时继承正确属主，否则卷根归 root、应用写不进去。
#
# 说明：
#   - 构建阶段 maven:3.9-eclipse-temurin-17，跑 `mvn package -pl <MODULE> -am`
#     （-am 同时构建依赖库模块 tf-shared / tf-redis / tf-hotspot）。
#   - Lombok 版本已在各模块 pom 钉为 1.18.42（兼容本机 JDK 17.0.19），标准 mvn package 即可编译。
#   - 构建期跳过测试：测试由 CI 的 build job 负责，镜像构建只为产出运行物。
#   - 配置全部走 classpath application.yml + 环境变量覆盖（见 deploy/env/.env.example），
#     不依赖任何本地 file:./application.yml 覆盖文件，容器即「克隆即跑」形态。
# =============================================================================
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre

# ───────────────────────────── 构建阶段 ─────────────────────────────
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

ARG MODULE
# 只先拷 POM：pom 不变时依赖层命中缓存，源码改动不会污染这一层。
# 新增模块后必须在这里补一行 COPY，否则依赖预取会漏掉它（package 阶段仍能过，只是慢）。
COPY pom.xml ./
COPY tf-shared/pom.xml      tf-shared/
COPY tf-redis/pom.xml       tf-redis/
COPY tf-hotspot/pom.xml     tf-hotspot/
COPY tf-feed-engine/pom.xml tf-feed-engine/
COPY tf-counter/pom.xml     tf-counter/
COPY tf-gateway/pom.xml     tf-gateway/
COPY tf-benchmark/pom.xml   tf-benchmark/

# 依赖预取。`|| true` 是刻意的：go-offline 对多模块工程并不保证 100% 完整
# （插件/传递依赖偶有遗漏），失败不该让构建挂掉——下一步 package 会把缺的补齐。
RUN mvn -B -q -pl ${MODULE} -am dependency:go-offline || true

# 再拷源码（.dockerignore 已排除 target / node_modules / docs / turbo-feed-ui 等）
COPY . .

RUN mvn -B -q package -pl ${MODULE} -am -DskipTests

# ───────────────────────────── 运行阶段 ─────────────────────────────
FROM ${RUNTIME_IMAGE} AS runtime

# curl 仅用于健康检查。装完立即清 apt 索引，不把它带进最终层。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --shell /usr/sbin/nologin --home-dir /app app

WORKDIR /app
ARG MODULE
# 各模块 artifactId 与目录名一致（tf-gateway / tf-feed-engine / tf-counter）
COPY --from=build /build/${MODULE}/target/${MODULE}-*.jar /app/app.jar

# 日志目录：behavior-log 落盘位置。/app 整体归 app 用户，named volume 首挂时继承该属主。
RUN mkdir -p /app/logs && chown -R app:app /app

# 容器感知的 JVM 参数。MaxRAMPercentage 必须显式设置：
# 不设时 JVM 用 1/4 宿主机内存算堆，容器限额更小时会被 OOMKilled 而不是正常 GC。
# ExitOnOutOfMemoryError：OOM 后直接退出交给 restart 策略拉起，好过进程「半死」继续接流量。
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
ENV SERVER_PORT=8080

USER app

EXPOSE 8080

# shell form：`$$` 被 Docker 转义成 `$`，由容器内 shell 在运行期展开 SERVER_PORT。
# start-period 给 60s：ShardingSphere 启动期加载元数据，冷启动比普通 Spring Boot 慢。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:$${SERVER_PORT:-8080}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
