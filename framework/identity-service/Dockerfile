# 身份界面独立构建，Node/pnpm 不进入最终 JRE；版本与官方 manifest 摘要固定。
FROM node:24.13.0-alpine@sha256:cd6fb7efa6490f039f3471a189214d5f548c11df1ff9e5b181aa49e22c14383e AS admin-build
WORKDIR /build/identity-service/admin
RUN npm install --global pnpm@11.12.0 --ignore-scripts
COPY identity-service/admin/package.json identity-service/admin/pnpm-lock.yaml identity-service/admin/.npmrc ./
RUN pnpm install --frozen-lockfile --ignore-scripts
COPY identity-service/admin ./
RUN pnpm run verify:build

FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /build
COPY pom.xml ./
COPY foundation/pom.xml foundation/
COPY api-gateway/pom.xml api-gateway/
COPY identity-service/pom.xml identity-service/
RUN mvn -B -pl identity-service -am dependency:go-offline -DskipTests
COPY foundation foundation
COPY api-gateway api-gateway
COPY identity-service identity-service
COPY --from=admin-build /build/identity-service/target/generated-resources /build/identity-service/target/generated-resources
RUN mvn -B -pl identity-service -am package -DskipTests -DskipAdminBuild=true

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY --from=build /build/identity-service/target/identity-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
