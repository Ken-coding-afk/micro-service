import uuid
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from ken_platform_sdk.context import request_context
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.security import CurrentPrincipal, DataScope

from sample_service.api.items import item_service, read_permission, router, write_permission
from sample_service.clients.peer import PeerClient
from sample_service.config import BusinessSettings
from sample_service.repositories.items import ItemRepository
from sample_service.schemas.items import Item, ItemPage
from sample_service.services.items import ItemService


async def test_expired_work_never_opens_transaction_or_writes():
    import time
    from unittest.mock import Mock

    from ken_platform_sdk.errors import DeadlineExceeded

    transaction = Mock()
    repository = AsyncMock()
    service = ItemService(repository, transaction, BusinessSettings())
    with request_context(deadline=time.time() - 1), pytest.raises(DeadlineExceeded):
        await service.create(principal(), "item")
    transaction.assert_not_called()
    repository.create.assert_not_awaited()


def principal(scope=DataScope.SELF):
    return CurrentPrincipal(
        str(uuid.uuid4()),
        "user",
        uuid.uuid4(),
        "fixture",
        frozenset(),
        frozenset({"python-item:read"}),
        {"python-item:read": scope},
        frozenset(),
        9999999999,
    )


@pytest.mark.asyncio
async def test_use_case_owns_identity_page_limit_and_transaction_failure():
    repository = AsyncMock(spec=ItemRepository)
    events = []
    session = object()

    @asynccontextmanager
    async def transaction():
        events.append("enter")
        try:
            yield session
        except Exception:
            events.append("rollback")
            raise
        else:
            events.append("commit")

    service = ItemService(repository, transaction, BusinessSettings(max_page_size=30))
    user = principal()
    item = await service.create(user, "record")
    assert item.name == "record"
    assert uuid.UUID(item.id)
    repository.create.assert_awaited_once_with(
        session, item, user.tenant_id, uuid.UUID(user.subject)
    )
    assert events == ["enter", "commit"]
    repository.list.return_value = ItemPage(items=[item], total=1, page=2, size=30, has_more=False)
    result = await service.list(user, 2, 100)
    assert result.model_dump() == {
        "items": [{"id": item.id, "name": "record"}],
        "total": 1,
        "page": 2,
        "size": 30,
        "has_more": False,
    }
    repository.list.assert_awaited_once_with(
        session, user.tenant_id, uuid.UUID(user.subject), True, 2, 30
    )
    repository.create.side_effect = ValueError("database fixture failure")
    with pytest.raises(ValueError, match="database fixture"):
        await service.create(user, "failure")
    assert events[-2:] == ["enter", "rollback"]


@pytest.mark.asyncio
async def test_no_tenant_cannot_enter_database_transaction():
    user = principal()
    from dataclasses import replace

    user = replace(user, tenant_id=None)
    repository = AsyncMock(spec=ItemRepository)

    @asynccontextmanager
    async def forbidden_transaction():
        pytest.fail("must reject before opening transaction")
        yield

    with pytest.raises(PlatformError):
        await ItemService(repository, forbidden_transaction, BusinessSettings()).create(
            user, "item"
        )
    repository.create.assert_not_awaited()


def test_http_fields_validation_and_pagination_contract():
    # 此处只验证路由合同；真实认证/隔离由独立项目回归覆盖。
    from ken_platform_sdk.web import PlatformMiddleware, install_error_handlers

    app = FastAPI()
    app.add_middleware(PlatformMiddleware)
    install_error_handlers(app)
    app.include_router(router)
    service = AsyncMock(spec=ItemService)
    item = Item(id=str(uuid.uuid4()), name="record")
    service.create.return_value = item
    service.list.return_value = ItemPage(items=[item], total=1, page=0, size=25, has_more=False)
    app.dependency_overrides[item_service] = lambda: service
    app.dependency_overrides[read_permission] = principal
    app.dependency_overrides[write_permission] = principal
    with (
        request_context(request_id="test-request"),
        TestClient(app, headers={"X-Request-Id": "test-request"}) as client,
    ):
        result = client.post("/api/v1/python-items", json={"name": "record"})
        assert result.status_code == 201
        assert result.json() == {
            "code": "OK",
            "message": "success",
            "data": item.model_dump(),
            "request_id": "test-request",
        }
        for body in [{"name": ""}, {"name": "x", "tenant_id": str(uuid.uuid4())}]:
            assert client.post("/api/v1/python-items", json=body).status_code == 400
        page = client.get("/api/v1/python-items").json()
        assert page["data"] == service.list.return_value.model_dump()
        assert service.list.await_args.args[1:] == (0, 25)
        for query in [
            "page=-1",
            "page=10001",
            "size=0",
            "size=101",
            "size=x",
            "page_size=20",
            "size=2&page_size=20",
        ]:
            rejected = client.get("/api/v1/python-items?" + query)
            assert rejected.status_code == 400
            assert rejected.json()["code"] == "VALIDATION_ERROR"


@pytest.mark.asyncio
async def test_peer_preserves_json_and_does_not_hide_downstream_failure():
    from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
    from ken_platform_sdk.http import ServiceHttpClient

    class Provider:
        async def query(self, service):
            return [ServiceInstance("peer.invalid", 80)]

    discovery = ServiceDiscovery(Provider())
    await discovery.refresh("sample-java-service")
    body = {
        "code": "OK",
        "message": "success",
        "data": {"items": [], "total": 0, "page": 0, "size": 25, "has_more": False},
        "request_id": "remote",
    }
    requests = []

    def handler(request):
        requests.append(request)
        return (
            httpx.Response(200, json=body)
            if len(requests) == 1
            else httpx.Response(503, json={"code": "SERVICE_BUSY", "message": "private details"})
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as transport:
        http = ServiceHttpClient(discovery, transport)
        peer = PeerClient(http, "sample-java-service")
        assert (await peer.list()).model_dump() == body
        assert requests[0].url.path == "/api/v1/java-items"
        with pytest.raises(PlatformError) as caught:
            await peer.list()
        assert caught.value.code == "SERVICE_BUSY"
        assert caught.value.status == 503
        assert "private" not in str(caught.value)
        with pytest.raises(PlatformError):
            await PeerClient(http, None).list()
        assert len(requests) == 2
