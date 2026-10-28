from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from grpc import RpcError

from ken_platform_sdk.errors import DependencyUnavailable
from ken_platform_sdk.nacos import NacosProvider
from test_config import settings


async def test_confirmation_always_uses_direct_network_request_not_sdk_cached_list(tmp_path):
    provider = NacosProvider(settings(tmp_path))
    direct = AsyncMock(return_value=SimpleNamespace(hosts=[]))
    cached = AsyncMock(side_effect=AssertionError("不能把缓存当成网络确认"))
    provider.naming = SimpleNamespace(
        grpc_client_proxy=SimpleNamespace(do_subscribe=direct), list_instances=cached
    )
    assert await provider.query("analysis") == []
    direct.assert_awaited_once_with("analysis", "DEFAULT_GROUP", "")
    direct.side_effect = RpcError("connection lost")
    with pytest.raises(DependencyUnavailable):
        await provider.query("analysis")
    assert direct.await_count == 2
    cached.assert_not_awaited()


async def test_owned_cache_is_cleaned_after_working_directory_changes(tmp_path, monkeypatch):
    from ken_platform_sdk import nacos

    monkeypatch.chdir(tmp_path)
    config = SimpleNamespace(shutdown=AsyncMock())
    naming = SimpleNamespace(shutdown=AsyncMock())
    monkeypatch.setattr(
        nacos.NacosConfigService, "create_config_service", AsyncMock(return_value=config)
    )
    monkeypatch.setattr(
        nacos.NacosNamingService, "create_naming_service", AsyncMock(return_value=naming)
    )
    provider = NacosProvider(settings(tmp_path, nacos_log_dir=Path("logs")))
    await provider.open()
    cache = Path(provider._cache.name)
    assert cache.is_relative_to(tmp_path / "logs")
    assert cache.is_dir()
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    monkeypatch.chdir(elsewhere)
    await provider.close()
    assert not cache.exists()
    assert provider._cache is None
    config.shutdown.assert_awaited_once()
    naming.shutdown.assert_awaited_once()
    await provider.close()
