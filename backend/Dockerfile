# MindSafe 后端 - 多阶段构建
# Stage 1: Maven 构建
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# 先复制 pom 利用 Docker 缓存层
COPY pom.xml .
COPY counseling-common/pom.xml counseling-common/
COPY counseling-tenant/pom.xml counseling-tenant/
COPY counseling-domain/pom.xml counseling-domain/
COPY counseling-ai/pom.xml counseling-ai/
COPY counseling-service/pom.xml counseling-service/
COPY counseling-api/pom.xml counseling-api/
COPY counseling-app/pom.xml counseling-app/
RUN mvn dependency:go-offline -B -q 2>/dev/null || true

# 复制源码并构建
COPY . .
RUN mvn package -DskipTests -B -q

# Stage 2: 运行时
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S mindsafe && adduser -S mindsafe -G mindsafe

COPY --from=builder /build/counseling-app/target/*.jar app.jar

RUN chown -R mindsafe:mindsafe /app
USER mindsafe

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=40s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
