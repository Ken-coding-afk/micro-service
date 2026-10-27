"""应用装配；导入无环境副作用，资源由 lifespan 管理。"""

import asyncio
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
import uvicorn
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from ken_platform_sdk.config import PlatformSettings
from ken_platform_sdk.db import database_loop_factory, tenant_transaction
from ken_platform_sdk.lifecycle import PlatformRuntime
from ken_platform_sdk.security import ResourceIdentitySettings, ResourceIntrospector
from ken_platform_sdk.security_web import install_resource_security, resource_identity_readiness
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from sample_service.api.items import router
from sample_service.clients.peer import PeerClient
from sample_service.config import BusinessSettings, database_url
from sample_service.repositories.items import ItemRepository, verify_database
from sample_service.services.items import ItemService


def create_app() -> FastAPI:
    settings = PlatformSettings.from_environment("sample-python-service")
    runtime = PlatformRuntime(settings)
    client = httpx.AsyncClient(follow_redirects=False)
    introspector = ResourceIntrospector(
        runtime.discovery, client, ResourceIdentitySettings.from_environment(os.environ)
    )
    engine = create_async_engine(database_url(), pool_pre_ping=True)
    sessions = async_sessionmaker(engine, expire_on_commit=False)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        async def check() -> None:
            assert runtime.parameters is not None
            app.state.business = BusinessSettings.model_validate(
                runtime.parameters.model_dump()["business"]
            )
            await verify_database(engine)
            app.state.items = ItemService(
                ItemRepository(), lambda: tenant_transaction(sessions), app.state.business
            )
            await resource_identity_readiness(runtime, introspector)
            if os.environ.get("PEER_SERVICE"):
                await runtime.watch(os.environ["PEER_SERVICE"])

        try:
            await runtime.start(checks=[check])
            assert runtime.http is not None
            app.state.peer = PeerClient(runtime.http, os.environ.get("PEER_SERVICE"))
            yield
        finally:
            await runtime.stop()
            await client.aclose()
            await engine.dispose()

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.get("/actuator/health/readiness")
    async def ready() -> JSONResponse:
        return JSONResponse(
            {"status": "UP" if runtime.ready else "DOWN"},
            status_code=200 if runtime.ready else 503,
        )

    app.include_router(router)
    runtime.install(app)
    install_resource_security(app, introspector)
    return app


def main() -> None:
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
