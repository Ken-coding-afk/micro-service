import asyncio
from contextlib import asynccontextmanager
from types import SimpleNamespace

import pytest

from ken_platform_sdk.db import DatabaseReadiness
from ken_platform_sdk.lifecycle import PlatformRuntime
from test_config import settings
from test_lifecycle import Provider


def engine_for(result=False, error=None, hanging=False):
    async def execute(statement, parameters):
        assert parameters == {"user": "runtime", "database": "business"}
        if hanging:
            await asyncio.Event().wait()
        if error:
            raise error
        return SimpleNamespace(scalar_one=lambda: result)

    @asynccontextmanager
    async def connect():
        yield SimpleNamespace(execute=execute)

    return SimpleNamespace(connect=connect)


@pytest.mark.parametrize("unsafe", [True, None, 0, "false"])
async def test_any_non_boolean_safe_result_fails_closed(unsafe):
    database = DatabaseReadiness(engine_for(unsafe), "runtime", "business")
    assert await database.check() is False
    with pytest.raises(ValueError, match="最小权限"):
        await database.verify()


async def test_database_outage_and_recovery_do_not_reuse_success():
    database = DatabaseReadiness(engine_for(), "runtime", "business")
    assert await database.check()
    database.engine = engine_for(error=RuntimeError("password=secret"))
    assert not await database.check()
    with pytest.raises(ValueError) as failure:
        await database.verify()
    assert "secret" not in str(failure.value)
    database.engine = engine_for()
    assert await database.check()


async def test_database_connection_and_query_are_bounded():
    database = DatabaseReadiness(engine_for(hanging=True), "runtime", "business")
    assert await asyncio.wait_for(database.check(), timeout=3) is False


async def test_runtime_readiness_is_dynamic_without_rerunning_assembly(tmp_path):
    provider = Provider()
    runtime = PlatformRuntime(settings(tmp_path), provider)
    healthy = True
    calls = []

    async def setup():
        calls.append("assemble")

    async def probe():
        return healthy

    await runtime.start([setup], readiness_checks=[probe])
    try:
        assert await runtime.is_ready()
        healthy = False
        assert not await runtime.is_ready()
        healthy = True
        assert await runtime.is_ready()
        assert calls == ["assemble"] and provider.calls.count("register") == 1
    finally:
        await runtime.stop()
    assert not await runtime.is_ready()


async def test_startup_readiness_failure_precedes_registration(tmp_path):
    provider = Provider()
    runtime = PlatformRuntime(settings(tmp_path), provider)

    async def probe():
        return False

    with pytest.raises(Exception, match="依赖检查"):
        await runtime.start(readiness_checks=[probe])
    assert not runtime.ready and "register" not in provider.calls
