# =============================================================================
# turbo-feed 多阶段构建镜像（参数化：以 MODULE 指定要打包的 Maven 子模块）
#
# 用法（仓库根目录执行）：
#   docker build -f Dockerfile --build-arg MODULE=tf-gateway    -t turbo-feed-gateway .
#   docker build -f Dockerfile --build-arg MODULE=tf-feed-engine -t turbo-feed-engine .
#   docker build -f Dockerfile --build-arg MODULE=tf-counter     -t turbo-feed-counter .
# 也可由 docker-compose.yml 直接 build（已配置 build.args.MODULE）。
#
# 说明：
#   - 构建阶段 maven:3.9-eclipse-temurin-17，跑 `mvn package -pl <MODULE> -am`
#     （-am 同时构建依赖库模块 tf-shared / tf-redis / tf-hotspot）。
#   - Lombok 版本已在各模块 pom 钉为 1.18.42（兼容本机 JDK 17.0.19），标准 mvn package 即可编译。
#   - 运行阶段 eclipse-temurin:17-jre（仅 JRE），并装 curl 供 /actuator/health 探活。
#   - 应用配置全部走 classpath application.yml + 环境变量覆盖（见 docker-compose.yml），
#     不依赖任何本地 file:./application.yml 覆盖文件，容器即「克隆即跑」形态。
# =============================================================================
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
# 全量拷贝源码（.dockerignore 已排除 target / node_modules / docs / turbo-feed-ui 等无关内容）
COPY . .
ARG MODULE
# 跳过测试以加速镜像构建；需在构建期跑测试则去掉 -DskipTests
RUN mvn -B -q package -pl ${MODULE} -am -DskipTests

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
ARG MODULE
# 各模块 artifactId 与目录名一致（tf-gateway / tf-feed-engine / tf-counter）
COPY --from=build /build/${MODULE}/target/${MODULE}-*.jar /app/app.jar
USER root
# 装 curl 供容器健康检查访问 /actuator/health（生产镜像可用 distroless + 自有探针替代）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# 生产部署建议改为非 root 运行（此处保持简单，便于排查）
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
