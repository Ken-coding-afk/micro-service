# 身份服务镜像：Java 构建 + JRE 运行两个阶段。
# T-07A 交付管理界面时在 Java 构建阶段之前增加 Node/pnpm 构建阶段，
# 将 identity-service/admin 静态产物打入同一镜像（设计 06·源码目录、构建与登录入口）。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY foundation/pom.xml foundation/
COPY api-gateway/pom.xml api-gateway/
COPY identity-service/pom.xml identity-service/
RUN mvn -B -pl identity-service -am dependency:go-offline -DskipTests
COPY foundation foundation
COPY api-gateway api-gateway
COPY identity-service identity-service
RUN mvn -B -pl identity-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/identity-service/target/identity-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
