# syntax=docker/dockerfile:1
#
# HoyoAgent 运行镜像（Spring Boot 4 + JDK 25）。
# 镜像里不含 .env、不含任何凭据；凭据只能来自环境变量或挂到 /data 的卷。
#
#   docker build -t hoyoagent:latest .
#   docker build -t hoyoagent:latest \
#     --build-arg BUILD_IMAGE=xxx/eclipse-temurin:25-jdk \
#     --build-arg RUNTIME_IMAGE=xxx/eclipse-temurin:25-jre .

ARG BUILD_IMAGE=docker.io/eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=docker.io/eclipse-temurin:25-jre

# ── 构建 ─────────────────────────────────────────────
FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

# mvnw 是 only-script 模式（仓库里没有 maven-wrapper.jar），靠 curl/wget 自己下 Maven；
# Temurin 镜像不保证自带，显式装
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# 先只拷 pom 和 wrapper：pom 不变时这层命中缓存，改源码不会重拉依赖
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/

# 缓存挂载 ~/.m2：改源码后重新打包不用重下依赖（需要 BuildKit，现代 Docker 默认开启）
# 测试里有联网用例，镜像里只打包不跑测试
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -s .mvn/settings.xml -DskipTests package \
 && cp target/*.jar /workspace/app.jar

# ── 运行 ─────────────────────────────────────────────
FROM ${RUNTIME_IMAGE}

ARG APP_UID=10001

# -Duser.home=/data 是这套部署的支点：账号凭据 / 签到记录 / device-id 都在 $HOME/.hoyo 下，
# 指向 /data 后一个卷就能把三样全持久化
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    HOME=/data \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.home=/data -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

WORKDIR /app

# 必须在 USER 之前建目录并 chown：命名卷首次挂载会复制镜像里目录的属主
RUN mkdir -p /app /data/.hoyo \
 && chown -R ${APP_UID}:${APP_UID} /app /data

COPY --from=build --chown=${APP_UID}:${APP_UID} /workspace/app.jar /app/app.jar

USER ${APP_UID}:${APP_UID}

EXPOSE 8080

# 运行镜像里没有 curl，用 bash 的 /dev/tcp 探端口
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD timeout 3 bash -c '</dev/tcp/127.0.0.1/8080' || exit 1

# exec：让 java 顶替 shell 成为 PID 1，SIGTERM 才能落到 Spring 的优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "hoyoagent"]