import os
import subprocess
import sys
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from sample_service import main
from sample_service.clients.peer import PeerClient
from sample_service.services.items import ItemService


def test_import_needs_no_runtime_secrets_or_network():
    environment = {
        k: v
        for k, v in os.environ.items()
        if not k.startswith(("DB_", "NACOS_", "PG_", "SERVICE_", "INTROSPECTION_"))
    }
    code = "import socket; socket.create_connection=lambda *a,**k: (_ for _ in ()).throw(AssertionError('network')); import sample_service.main"
    result = subprocess.run([sys.executable, "-c", code], env=environment, capture_output=True)
    assert result.returncode == 0, result.stderr.decode(errors="replace")


@pytest.mark.asyncio
async def test_lifespan_assembles_once_in_order_and_closes_after_failure(monkeypatch):
    runtime = SimpleNamespace(
        parameters=SimpleNamespace(model_dump=lambda: {"business": {"max_page_size": 40}}),
        http=AsyncMock(),
        ready=True,
        discovery=object(),
        stop=AsyncMock(),
        watch=AsyncMock(),
    )

    async def start(*, checks):
        for check in checks:
            await check()

    runtime.start = start
    order = []
    runtime.install = lambda app: order.append("runtime")
    connection = SimpleNamespace(
        execute=AsyncMock(return_value=SimpleNamespace(one=lambda: (False, False)))
    )

    @asynccontextmanager
    async def connect():
        yield connection

    engine = SimpleNamespace(connect=connect, dispose=AsyncMock())
    client = SimpleNamespace(aclose=AsyncMock())
    monkeypatch.setattr(main.PlatformSettings, "from_environment", lambda _: object())
    monkeypatch.setattr(main, "PlatformRuntime", lambda _: runtime)
    monkeypatch.setattr(main.httpx, "AsyncClient", lambda **_: client)
    monkeypatch.setattr(main.ResourceIdentitySettings, "from_environment", lambda _: object())
    monkeypatch.setattr(main, "ResourceIntrospector", lambda *args: object())
    monkeypatch.setattr(main, "database_url", lambda: "fixture")
    monkeypatch.setattr(main, "create_async_engine", lambda *a, **k: engine)
    monkeypatch.setattr(main, "async_sessionmaker", lambda *a, **k: Mock())
    monkeypatch.setattr(main, "resource_identity_readiness", AsyncMock())
    monkeypatch.setattr(main, "install_resource_security", lambda *args: order.append("security"))
    monkeypatch.delenv("PEER_SERVICE", raising=False)
    app = main.create_app()
    assert order == ["runtime", "security"]
    with pytest.raises(ValueError, match="application fixture"):
        async with app.router.lifespan_context(app):
            assert isinstance(app.state.items, ItemService)
            assert app.state.items.settings.max_page_size == 40
            assert isinstance(app.state.peer, PeerClient)
            raise ValueError("application fixture")
    runtime.stop.assert_awaited_once()
    client.aclose.assert_awaited_once()
    engine.dispose.assert_awaited_once()
