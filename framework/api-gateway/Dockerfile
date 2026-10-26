# 网关镜像：Java 构建 + JRE 运行两个阶段。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY foundation/pom.xml foundation/
COPY identity-service/pom.xml identity-service/
COPY api-gateway/pom.xml api-gateway/
RUN mvn -B -pl api-gateway -am dependency:go-offline -DskipTests
COPY foundation foundation
COPY identity-service identity-service
COPY api-gateway api-gateway
RUN mvn -B -pl api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/api-gateway/target/api-gateway.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
