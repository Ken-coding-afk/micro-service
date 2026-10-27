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
