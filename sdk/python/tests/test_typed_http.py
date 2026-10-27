import asyncio

import httpx
import pytest
from pydantic import BaseModel

from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.http import ServiceHttpClient


class Item(BaseModel):
    id: int
    note: str | None = None


async def client_for(status, body):
    class Provider:
        async def query(self, service):
            return [ServiceInstance("peer.example", 80)]

    discovery = ServiceDiscovery(Provider())
    await discovery.refresh("peer")
    transport = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda req: httpx.Response(status, content=body))
    )
    return ServiceHttpClient(discovery, transport), transport


@pytest.mark.parametrize("body", [b'{"id":1,"future":true}', b'{"id":1,"note":null}'])
async def test_typed_forward_compatible_and_optional(body):
    client, transport = await client_for(200, body)
    async with transport:
        value = await client.request_model("peer", "GET", "/item", Item)
        assert value.id == 1 and value.note is None


@pytest.mark.parametrize("body", [b"{}", b'{"id":"1"}', b'{"id":null}', b"null", b"not-json", b""])
async def test_typed_rejects_invalid_required_field(body):
    client, transport = await client_for(200, body)
    async with transport:
        with pytest.raises(PlatformError) as error:
            await client.request_model("peer", "GET", "/item", Item)
    assert error.value.code == "DEPENDENCY_INVALID_RESPONSE"


async def test_typed_list_no_content_and_safe_error():
    for status, body, kind, expected in [
        (200, b'[{"id":2}]', list[Item], [Item(id=2)]),
        (204, b"", type(None), None),
    ]:
        client, transport = await client_for(status, body)
        async with transport:
            assert await client.request_model("peer", "GET", "/item", kind) == expected
    client, transport = await client_for(403, b'{"code":"NO_ACCESS","message":"private secret"}')
    async with transport:
        with pytest.raises(PlatformError) as error:
            await client.request_model("peer", "GET", "/item", Item)
    assert error.value.code == "NO_ACCESS" and error.value.status == 403
    assert "secret" not in str(error.value)


async def test_typed_cancel_propagates_to_transport():
    client, transport = await client_for(200, b"{}")
    entered, cancelled = asyncio.Event(), asyncio.Event()

    async def slow(*args, **kwargs):
        entered.set()
        try:
            await asyncio.Future()
        finally:
            cancelled.set()

    client.request = slow
    async with transport:
        task = asyncio.create_task(client.request_model("peer", "GET", "/item", Item))
        await entered.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert cancelled.is_set()
