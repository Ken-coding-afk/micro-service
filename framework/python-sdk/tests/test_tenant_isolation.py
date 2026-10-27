import asyncio
import json
import os
import uuid
from pathlib import Path

import pytest

sqlalchemy = pytest.importorskip("sqlalchemy", reason="此文件需要可选 db extra")
from sqlalchemy import URL, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from ken_platform_sdk.context import request_context
from ken_platform_sdk.db import tenant_transaction
from ken_platform_sdk.errors import PlatformError


@pytest.fixture
async def database():
    if not os.getenv("PLATFORM_TEST_DATABASE_BINDING"):
        pytest.skip("未提供隔离测试库连接描述")
    values = json.loads(
        Path(os.environ["PLATFORM_TEST_DATABASE_BINDING"]).read_text(encoding="utf-8")
    )
    assert (
        values["test_only"] and values["app_env"] == "local" and values["dbname"].startswith("t02t")
    )
    url = URL.create(
        "postgresql+psycopg",
        username=values["username"],
        password=Path(values["password_file"]).read_text(encoding="utf-8").strip(),
        host=values["host"],
        port=values["port"],
        database=values["dbname"],
    )
    engine = create_async_engine(url, pool_size=1, max_overflow=0)
    try:
        yield engine, async_sessionmaker(engine, expire_on_commit=False), values
    finally:
        await engine.dispose()


async def test_same_connection_reused_without_tenant_leak_after_exception(database):
    engine, factory, values = database
    with request_context(tenant_id=uuid.UUID(values["tenant_a"]), principal_id=values["user_a"]):
        async with tenant_transaction(factory) as session:
            pid = (await session.execute(text("SELECT pg_backend_pid()"))).scalar_one()
            assert (
                await session.execute(text("SELECT id::text FROM sys_user"))
            ).scalars().all() == [values["user_a"]]
        with pytest.raises(RuntimeError, match="rollback"):
            async with tenant_transaction(factory) as session:
                await session.execute(text("UPDATE sys_user SET display_name='must-rollback'"))
                raise RuntimeError("rollback")
    async with engine.connect() as connection:
        assert (await connection.execute(text("SELECT pg_backend_pid()"))).scalar_one() == pid
        assert (await connection.execute(text("SELECT count(*) FROM sys_user"))).scalar_one() == 0
    with request_context(tenant_id=uuid.UUID(values["tenant_b"]), principal_id=values["user_b"]):
        async with tenant_transaction(factory) as session:
            assert (
                await session.execute(text("SELECT id::text FROM sys_user"))
            ).scalars().all() == [values["user_b"]]


async def test_missing_context_fails_before_db_and_cross_tenant_write_rejected(database):
    engine, factory, values = database
    with pytest.raises(PlatformError) as missing:
        async with tenant_transaction(factory):
            pytest.fail("缺少上下文不能执行数据库操作")
    assert missing.value.code == "TENANT_CONTEXT_REQUIRED"
    with (
        request_context(tenant_id=uuid.UUID(values["tenant_a"]), principal_id=values["user_a"]),
        pytest.raises(sqlalchemy.exc.DBAPIError),
    ):
        async with tenant_transaction(factory) as session:
            await session.execute(
                text(
                    "INSERT INTO sys_user(tenant_id,normalized_username,display_name,password_hash) VALUES (:tenant,'forged','伪造','fixture')"
                ),
                {"tenant": uuid.UUID(values["tenant_b"])},
            )
    async with engine.connect() as connection:
        assert (await connection.execute(text("SELECT count(*) FROM sys_user"))).scalar_one() == 0


async def test_cancelled_task_rolls_back_and_releases_single_connection(database):
    engine, factory, values = database
    written = asyncio.Event()

    async def operation():
        with request_context(
            tenant_id=uuid.UUID(values["tenant_a"]), principal_id=values["user_a"]
        ):
            async with tenant_transaction(factory) as session:
                await session.execute(text("UPDATE sys_user SET display_name='must-rollback'"))
                written.set()
                await asyncio.Event().wait()

    pending = asyncio.create_task(operation())
    await asyncio.wait_for(written.wait(), 3)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    async with asyncio.timeout(3), engine.connect() as connection:
        assert (await connection.execute(text("SELECT count(*) FROM sys_user"))).scalar_one() == 0
    with request_context(tenant_id=uuid.UUID(values["tenant_a"]), principal_id=values["user_a"]):
        async with tenant_transaction(factory) as session:
            assert (
                await session.execute(text("SELECT display_name FROM sys_user"))
            ).scalar_one() == "用户"
