# syntax=docker/dockerfile:1

# ===== 构建阶段 =====
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# 缓存依赖：先复制 pom，再复制源码（分层构建加速）
COPY pom.xml ./pom.xml
COPY hermes-server/pom.xml ./hermes-server/pom.xml
RUN mvn -pl hermes-server -am dependency:go-offline -B -q

# 复制源码与前端构建产物（前端已预构建进 hermes-server/src/main/resources/static）
COPY hermes-server ./hermes-server

# 构建后端 jar（跳过测试，前端 static 已在 resources 内）
RUN mvn -pl hermes-server -am package -DskipTests -B -q

# ===== 运行阶段 =====
FROM eclipse-temurin:21-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS=""

RUN apt-get update && apt-get install -y --no-install-recommends tini \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/hermes-server/target/hermes-server-*.jar /app/hermes-server.jar

EXPOSE 8080

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/hermes-server.jar"]
