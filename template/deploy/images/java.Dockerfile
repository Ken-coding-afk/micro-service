# 使用经过构建检查的不可变 JAR 制品打镜像；构建上下文只含 app.jar。
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
