FROM python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a AS build
RUN pip install --no-cache-dir uv==0.13.5
WORKDIR /build/services/sample-python-service
COPY . /build
RUN uv lock --check && uv sync --frozen --no-dev --no-editable --compile-bytecode
FROM build AS migration-build
RUN uv sync --frozen --no-dev --group migration --no-editable --compile-bytecode
FROM python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a AS migration
COPY --from=migration-build /build/services/sample-python-service/.venv /opt/venv
COPY --from=build /build/services/sample-python-service/migrations /migrations
ENV PATH="/opt/venv/bin:$PATH"
WORKDIR /migrations
ENTRYPOINT ["/opt/venv/bin/python", "-m", "alembic", "-c", "/migrations/alembic.ini"]
FROM python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a AS runtime
WORKDIR /app
COPY --from=build /build/services/sample-python-service/.venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
ENTRYPOINT ["/opt/venv/bin/python", "-c", "from sample_service.main import main; main()"]
