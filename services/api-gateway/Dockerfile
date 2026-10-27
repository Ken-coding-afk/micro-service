# 网关镜像：Java 构建 + JRE 运行两个阶段。
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /build
COPY pom.xml ./
COPY java-sdk/pom.xml java-sdk/
COPY services/identity-service/pom.xml services/identity-service/
COPY services/api-gateway/pom.xml services/api-gateway/
RUN mvn -B -pl :api-gateway -am dependency:go-offline -DskipTests
COPY java-sdk java-sdk
COPY services/identity-service services/identity-service
COPY services/api-gateway services/api-gateway
RUN mvn -B -pl :api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY --from=build /build/services/api-gateway/target/api-gateway.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
