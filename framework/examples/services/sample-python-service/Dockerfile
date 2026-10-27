# wheels/ 包含已构建的项目 wheel、SDK 与锁定依赖；构建前按发行清单校验摘要。
FROM python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a
WORKDIR /app
COPY wheels /wheels
RUN pip install --no-cache-dir --no-index --find-links=/wheels sample-python-service==0.1.0
EXPOSE 8083
ENTRYPOINT ["sample-api"]
