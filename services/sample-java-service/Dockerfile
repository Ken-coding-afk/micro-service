FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /build
COPY . .
RUN mvn -B -f pom.xml -pl :sample-java-service -am package -DskipTests
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY --from=build /build/services/sample-java-service/target/sample-java-service.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
