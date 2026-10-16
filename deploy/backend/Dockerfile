# ============================================================================
#  后端镜像（多阶段构建）
#  阶段一 builder：Maven + JDK 21 编译打包
#  阶段二 runtime：只带 JRE，镜像小、启动快
#  构建上下文是仓库根目录（见 docker-compose.yml 的 context: ..）
# ============================================================================

# ---------------------------------------------------------------- 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先只拷 pom，利用 Docker 层缓存：依赖没变就不重复下载
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline

# 再拷源码编译
COPY backend/src ./src
RUN mvn -B -q clean package -DskipTests

# ---------------------------------------------------------------- 运行阶段
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -Dfile.encoding=UTF-8"

# 健康检查用 curl
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fs http://localhost:8080/api/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
