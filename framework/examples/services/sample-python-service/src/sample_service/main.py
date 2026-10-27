"""普通资源示例：身份来自 SDK，领域数据由本服务负责。"""

import asyncio
import os
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

import httpx
import uvicorn
from fastapi import Depends, FastAPI, Query
from fastapi.responses import JSONResponse
from ken_platform_sdk.config import PlatformSettings
from ken_platform_sdk.context import current_context
from ken_platform_sdk.db import database_loop_factory, tenant_transaction
from ken_platform_sdk.lifecycle import PlatformRuntime
from ken_platform_sdk.security import (
    CurrentPrincipal,
    DataScope,
    ResourceIdentitySettings,
    ResourceIntrospector,
)
from ken_platform_sdk.security_web import (
    install_resource_security,
    require_permission,
    resource_identity_readiness,
)
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import URL, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine


def database_url():
    password = (
        Path(os.environ["DB_PASSWORD_SECRET_FILE"]).read_text(encoding="utf8").strip()
    )
    return URL.create(
        "postgresql+psycopg",
        username=os.environ["DB_USER"],
        password=password,
        host=os.environ["PG_HOST"],
        port=int(os.environ["PG_PORT"]),
        database=os.environ["DB_NAME"],
        query={
            "sslmode": "disable" if os.environ["APP_ENV"] == "local" else "verify-full"
        },
    )


class CreateItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=120)


class BusinessSettings(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)
    max_page_size: int = Field(default=100, ge=20, le=100)


def response(data):
    return {
        "code": "OK",
        "message": "success",
        "data": data,
        "request_id": current_context.get().request_id,
    }


def create_app():
    settings = PlatformSettings.from_environment("sample-python-service")
    runtime = PlatformRuntime(settings)
    client = httpx.AsyncClient(follow_redirects=False)
    introspector = ResourceIntrospector(
        runtime.discovery, client, ResourceIdentitySettings.from_environment(os.environ)
    )
    engine = create_async_engine(database_url(), pool_pre_ping=True)
    sessions = async_sessionmaker(engine, expire_on_commit=False)

    @asynccontextmanager
    async def lifespan(app):
        async def check():
            assert runtime.parameters is not None
            app.state.business = BusinessSettings.model_validate(
                runtime.parameters.model_dump()["business"]
            )
            async with engine.connect() as conn:
                row = (
                    await conn.execute(
                        text(
                            "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname=current_user"
                        )
                    )
                ).one()
                if row[0] or row[1]:
                    raise ValueError("业务必须使用受限 runtime 数据库账号")
                await conn.execute(text("SELECT id FROM sample_item LIMIT 0"))
            await resource_identity_readiness(runtime, introspector)
            if os.environ.get("PEER_SERVICE"):
                await runtime.watch(os.environ["PEER_SERVICE"])

        try:
            await runtime.start(checks=[check])
            yield
        finally:
            await runtime.stop()
            await client.aclose()
            await engine.dispose()

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.get("/actuator/health/readiness")
    async def ready():
        return JSONResponse(
            {"status": "UP" if runtime.ready else "DOWN"},
            status_code=200 if runtime.ready else 503,
        )

    @app.post("/api/v1/python-items", status_code=201)
    async def create(
        value: CreateItem,
        principal: Annotated[
            CurrentPrincipal,
            Depends(require_permission("python.items.write", "python-item:create")),
        ],
    ):
        item_id = uuid.uuid4()
        async with tenant_transaction(sessions) as session:
            await session.execute(
                text(
                    "INSERT INTO sample_item(id,tenant_id,owner_user_id,name) VALUES(:id,:tenant,:owner,:name)"
                ),
                {
                    "id": item_id,
                    "tenant": principal.require_tenant(),
                    "owner": uuid.UUID(principal.subject),
                    "name": value.name,
                },
            )
        return response({"id": str(item_id), "name": value.name})

    @app.get("/api/v1/python-items")
    async def listing(
        principal: Annotated[
            CurrentPrincipal,
            Depends(require_permission("python.items.read", "python-item:read")),
        ],
        page: int = Query(1, ge=1),
        page_size: int = Query(20, ge=1, le=100),
    ):
        own = principal.data_scope("python-item:read") == DataScope.SELF
        page_size = min(page_size, app.state.business.max_page_size)
        params = {
            "tenant": principal.require_tenant(),
            "own": own,
            "owner": uuid.UUID(principal.subject),
            "limit": page_size,
            "offset": (page - 1) * page_size,
        }
        predicate = "tenant_id=:tenant AND (NOT :own OR owner_user_id=:owner)"
        async with tenant_transaction(sessions) as session:
            count = (
                await session.execute(
                    text("SELECT count(*) FROM sample_item WHERE " + predicate), params
                )
            ).scalar_one()
            rows = (
                (
                    await session.execute(
                        text(
                            "SELECT id,name FROM sample_item WHERE "
                            + predicate
                            + " ORDER BY id LIMIT :limit OFFSET :offset"
                        ),
                        params,
                    )
                )
                .mappings()
                .all()
            )
        return response(
            {
                "items": [{"id": str(row["id"]), "name": row["name"]} for row in rows],
                "total": count,
                "page": page,
                "page_size": page_size,
            }
        )

    @app.get("/api/v1/python-items/peer")
    async def peer(
        principal: Annotated[
            CurrentPrincipal,
            Depends(require_permission("python.items.read", "python-item:read")),
        ],
    ):
        from ken_platform_sdk.errors import PlatformError

        if os.environ.get("PEER_SERVICE") != "sample-java-service":
            raise PlatformError("PEER_NOT_CONFIGURED", "未启用 Java 协作示例", 503)
        result = await runtime.http.request(
            "sample-java-service", "GET", "/api/v1/java-items"
        )
        if result.status_code != 200:
            raise PlatformError(
                "PEER_REJECTED", "下游未返回成功结果", result.status_code
            )
        return result.json()

    runtime.install(app)
    install_resource_security(app, introspector)
    return app


def main():
    settings = PlatformSettings.from_environment("sample-python-service")
    server = uvicorn.Server(
        uvicorn.Config(
            create_app(),
            host=settings.service_bind_host,
            port=settings.service_bind_port,
        )
    )
    with asyncio.Runner(loop_factory=database_loop_factory) as runner:
        runner.run(server.serve())
