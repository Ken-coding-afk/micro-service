import asyncio
import time
import uuid

import httpx
import pytest
from fastapi import FastAPI

from ken_platform_sdk.context import current_context, request_context
from ken_platform_sdk.errors import DeadlineExceeded, DependencyUnavailable
from ken_platform_sdk.web import PlatformMiddleware


@pytest.mark.parametrize("deadline", ["9" * 600, "-" + "9" * 600, "invalid"])
async def test_extreme_external_deadline_is_bounded_without_float_overflow(deadline):
    app = FastAPI()
    app.add_middleware(PlatformMiddleware)

    @app.get("/budget")
    async def budget():
        remaining = current_context.get().deadline - time.time()
        if remaining <= 0:
            raise DeadlineExceeded()
        return {"remaining": remaining}

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.get("/budget", headers={"X-Request-Deadline": deadline})
    if deadline.startswith("-"):
        assert response.status_code == 504
        assert response.json()["code"] == "UPSTREAM_TIMEOUT"
    else:
        assert response.status_code == 200
        assert 0 < response.json()["remaining"] <= 10


async def test_concurrent_contexts_are_isolated_and_errors_restore_parent():
    tenants = [uuid.uuid4(), uuid.uuid4()]

    async def execute(tenant):
        with request_context(tenant_id=tenant, access_token="private-fixture") as outer:
            await asyncio.sleep(0)
            with pytest.raises(RuntimeError), request_context(request_id="nested"):
                assert current_context.get().tenant_id == tenant
                assert "private-fixture" not in repr(outer)
                raise RuntimeError("failure")
            assert current_context.get() == outer
            return current_context.get().tenant_id

    assert await asyncio.gather(*(execute(tenant) for tenant in tenants)) == tenants
    assert current_context.get() is None


def test_nested_scope_cannot_switch_tenant_or_extend_deadline():
    tenant = uuid.uuid4()
    with request_context(tenant_id=tenant, deadline=123):
        with pytest.raises(ValueError), request_context(tenant_id=uuid.uuid4()):
            pytest.fail("跨租户上下文不得进入")
        with request_context(deadline=456) as nested:
            assert nested.deadline == 123


async def test_external_identity_headers_never_create_principal_and_error_is_stable():
    app = FastAPI()
    app.add_middleware(PlatformMiddleware)

    @app.get("/probe")
    async def probe():
        context = current_context.get()
        assert context.tenant_id is None and context.principal_id is None
        assert context.deadline <= time.time() + 10
        raise DependencyUnavailable()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.get(
            "/probe",
            headers={
                "X-User-Id": "forged",
                "X-Tenant-Id": str(uuid.uuid4()),
                "X-Request-Deadline": str(int((time.time() + 9999) * 1000)),
            },
        )
    assert response.status_code == 503
    assert response.json()["code"] == "DEPENDENCY_UNAVAILABLE"
    assert response.json()["request_id"] == response.headers["x-request-id"]
    assert current_context.get() is None
