# wheels/ 包含已构建的项目 wheel、SDK 与锁定依赖；构建前按发行清单校验摘要。
FROM python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a
WORKDIR /app
COPY wheels /wheels
# 显式安装锁文件构建出的 wheel，包含已锁定的预发行依赖；不重新宽松解析版本。
RUN pip install --no-cache-dir --no-index --find-links=/wheels /wheels/*.whl && pip check
EXPOSE 8083
ENTRYPOINT ["sample-api"]
