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
    repository.list.return_value = ItemPage(items=[item], total=1, page=2, page_size=30)
    result = await service.list(user, 2, 100)
    assert result.model_dump() == {
        "items": [{"id": item.id, "name": "record"}],
        "total": 1,
        "page": 2,
        "page_size": 30,
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
    app = FastAPI()
    app.include_router(router)
    service = AsyncMock(spec=ItemService)
    item = Item(id=str(uuid.uuid4()), name="record")
    service.create.return_value = item
    service.list.return_value = ItemPage(items=[item], total=1, page=1, page_size=20)
    app.dependency_overrides[item_service] = lambda: service
    app.dependency_overrides[read_permission] = principal
    app.dependency_overrides[write_permission] = principal
    with request_context(request_id="test-request"), TestClient(app) as client:
        result = client.post("/api/v1/python-items", json={"name": "record"})
        assert result.status_code == 201
        assert result.json() == {
            "code": "OK",
            "message": "success",
            "data": item.model_dump(),
            "request_id": "test-request",
        }
        for body in [{"name": ""}, {"name": "x", "tenant_id": str(uuid.uuid4())}]:
            assert client.post("/api/v1/python-items", json=body).status_code == 422
        page = client.get("/api/v1/python-items").json()
        assert page["data"] == service.list.return_value.model_dump()
        assert client.get("/api/v1/python-items?page=0").status_code == 422
        assert client.get("/api/v1/python-items?page_size=101").status_code == 422


@pytest.mark.asyncio
async def test_peer_preserves_json_and_does_not_hide_downstream_failure():
    http = AsyncMock()
    http.request.return_value = httpx.Response(
        200, json={"data": {"items": []}, "request_id": "remote"}
    )
    peer = PeerClient(http, "sample-java-service")
    assert await peer.list() == {"data": {"items": []}, "request_id": "remote"}
    http.request.assert_awaited_once_with("sample-java-service", "GET", "/api/v1/java-items")
    http.request.return_value = httpx.Response(503, json={"code": "SERVICE_BUSY"})
    with pytest.raises(PlatformError) as caught:
        await peer.list()
    assert caught.value.code == "PEER_REJECTED"
    assert caught.value.status == 503
    http.reset_mock()
    with pytest.raises(PlatformError):
        await PeerClient(http, None).list()
    http.request.assert_not_awaited()
