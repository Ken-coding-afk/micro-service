"""实际 ASGI 路由和 SDK 内省合同；上游 HTTP 夹具不替代真实身份服务验收。"""

import asyncio
import copy
import json
import time
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import parse_qs

import httpx
import pytest
from fastapi import APIRouter, Depends, FastAPI

from ken_platform_sdk.context import current_context
from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.security import ResourceIdentitySettings, ResourceIntrospector
from ken_platform_sdk.security_web import (
    current_principal,
    install_resource_security,
    require_permission,
    resource_identity_readiness,
)

FIXTURE = json.loads(
    (Path(__file__).resolve().parents[2] / "test-fixtures/introspection-v1.json").read_text(
        encoding="utf-8"
    )
)
USER = "11111111-1111-4111-8111-111111111111"
TENANT = "22222222-2222-4222-8222-222222222222"
OTHER_USER = "33333333-3333-4333-8333-333333333333"
OTHER_TENANT = "44444444-4444-4444-8444-444444444444"


def claims(token):
    if token == "invalid" or token.startswith("startup-"):
        return {"active": False}
    value = copy.deepcopy(FIXTURE["cases"][0]["claims"])
    value.update(iat=int(time.time()), exp=int(time.time()) + 600)
    if token == "no-permission":
        value.update(permissions=[], data_scopes={})
    if token == "wrong-scope":
        value["scope"] = "documents.write"
    if token == "user-second":
        value.update(sub=OTHER_USER, tenant_id=OTHER_TENANT)
    if token.startswith("machine"):
        tenant = OTHER_TENANT if token == "machine-foreign" else TENANT
        value.update(
            sub="fixture-machine",
            client_id="fixture-machine",
            principal_type="service",
            tenant_id=tenant,
            allowed_tenant_ids=[tenant],
            scope="documents.read internal.forward",
            permissions=["document:read", "internal:forward"],
            data_scopes={"document:read": "TENANT"},
        )
    return value


class Provider:
    async def query(self, service):
        assert service == "identity-service"
        return [ServiceInstance("127.0.0.1", 18081)]


@asynccontextmanager
async def introspector(tmp_path):
    secret = tmp_path / "reader.password"
    secret.write_text("private-resource-fixture", encoding="utf-8")
    discovery = ServiceDiscovery(Provider())
    await discovery.refresh("identity-service")
    calls = []

    async def handle(request):
        token = parse_qs(request.content.decode())["token"][0]
        calls.append(token)
        await asyncio.sleep(0)
        return httpx.Response(503 if token == "unavailable" else 200, json=claims(token))

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        yield (
            ResourceIntrospector(
                discovery,
                http,
                ResourceIdentitySettings(
                    "local", FIXTURE["issuer"], FIXTURE["audience"], "fixture-reader", secret
                ),
            ),
            calls,
        )


def application(client):
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    async def result():
        await asyncio.sleep(0)
        principal = current_principal()
        context = current_context.get()
        return {
            "subject": principal.subject,
            "tenant": str(context.tenant_id),
            "request_id": context.request_id,
            "primary_token_preserved": context.access_token.startswith("user"),
            "scope": principal.data_scope("document:read").value,
        }

    user = require_permission("documents.read", "document:read")
    app.get("/api/v1/probe", dependencies=[Depends(user)])(result)
    app.post("/api/v1/probe", dependencies=[Depends(user)])(result)
    dual = require_permission(
        "documents.read",
        "document:read",
        service_scope="internal.forward",
        service_permission="internal:forward",
    )
    app.get("/internal/v1/forward", dependencies=[Depends(dual)])(result)
    machine = require_permission("internal.forward", "internal:forward", principal_type="service")
    app.get("/internal/v1/job", dependencies=[Depends(machine)])(result)

    @app.get("/actuator/health")
    async def health():
        return {"status": "UP", "anonymous": current_context.get().principal_id is None}

    install_resource_security(app, client)
    return app


async def test_bearer_scope_permission_type_and_claim_context_are_enforced(tmp_path):
    async with introspector(tmp_path) as (identity, calls):
        app = application(identity)
        async with (
            app.router.lifespan_context(app),
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as http,
        ):
            missing = await http.get(
                "/api/v1/probe?access_token=user-token",
                headers={"X-User-Id": USER, "X-Tenant-Id": TENANT, "Cookie": "IDENTITY_SSO=forged"},
            )
            assert missing.status_code == 401 and calls == []
            assert "invalid_token" in missing.headers["www-authenticate"]
            for token, status in [
                ("invalid", 401),
                ("no-permission", 403),
                ("wrong-scope", 403),
                ("machine-token", 403),
            ]:
                response = await http.get(
                    "/api/v1/probe", headers={"Authorization": "Bearer " + token}
                )
                assert response.status_code == status
                assert "Bearer" in response.headers["www-authenticate"]
            response = await http.post(
                "/api/v1/probe?tenant_id=forged",
                headers={
                    "Authorization": "Bearer user-token",
                    "X-Tenant-Id": OTHER_TENANT,
                    "X-User-Id": OTHER_USER,
                    "X-Request-Id": "resource-web-1",
                },
            )
            assert response.status_code == 200
            assert response.json() == {
                "subject": USER,
                "tenant": TENANT,
                "request_id": response.headers["x-request-id"],
                "primary_token_preserved": True,
                "scope": "SELF",
            }
            assert "set-cookie" not in response.headers
            assert (await http.get("/actuator/health")).json()["anonymous"] is True
        assert current_context.get() is None
        with pytest.raises(PlatformError):
            current_principal()


async def test_internal_dual_identity_keeps_user_and_machine_has_exact_tenant(tmp_path):
    async with introspector(tmp_path) as (identity, _):
        app = application(identity)
        async with (
            app.router.lifespan_context(app),
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as http,
        ):
            for machine, status in [
                (None, 401),
                ("user-token", 403),
                ("machine-foreign", 403),
                ("machine-token", 200),
            ]:
                headers = {"Authorization": "Bearer user-token"}
                if machine:
                    headers["X-Service-Authorization"] = "Bearer " + machine
                response = await http.get("/internal/v1/forward", headers=headers)
                assert response.status_code == status
                if status == 200:
                    assert response.json()["subject"] == USER
                    assert response.json()["primary_token_preserved"] is True
            machine = await http.get(
                "/internal/v1/job", headers={"Authorization": "Bearer machine-token"}
            )
            assert machine.status_code == 200
            assert machine.json()["subject"] == "fixture-machine"
            assert machine.json()["scope"] == "TENANT"


async def test_duplicate_bearer_dependency_errors_and_concurrent_context_cleanup(tmp_path):
    async with introspector(tmp_path) as (identity, calls):
        app = application(identity)
        async with (
            app.router.lifespan_context(app),
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as http,
        ):
            duplicate = await http.get(
                "/api/v1/probe",
                headers=[
                    ("Authorization", "Bearer user-token"),
                    ("Authorization", "Bearer invalid"),
                ],
            )
            assert duplicate.status_code == 401 and calls == []
            failure = await http.get(
                "/api/v1/probe", headers={"Authorization": "Bearer unavailable"}
            )
            assert failure.status_code == 503 and failure.json()["code"] == "IDENTITY_UNAVAILABLE"
            assert "private-resource-fixture" not in failure.text
            responses = await asyncio.gather(
                *[
                    http.get(
                        "/api/v1/probe",
                        headers={"Authorization": "Bearer " + token, "X-Request-Id": token},
                    )
                    for token in ["user-token", "user-second"]
                ]
            )
            assert [response.status_code for response in responses] == [200, 200]
            assert [response.json()["subject"] for response in responses] == [USER, OTHER_USER]
            assert [response.json()["tenant"] for response in responses] == [TENANT, OTHER_TENANT]
            assert current_context.get() is None


@pytest.mark.parametrize(
    "path,docs", [("/api/undeclared", False), ("/outside", False), ("/api/declared", True)]
)
async def test_startup_rejects_undeclared_bypass_and_public_docs_before_old_lifespan(
    tmp_path, path, docs
):
    entered = []

    @asynccontextmanager
    async def lifespan(app):
        entered.append(True)
        yield

    app = (
        FastAPI(lifespan=lifespan)
        if docs
        else FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    )

    async def empty():
        return {}

    app.get(path)(empty)
    async with introspector(tmp_path) as (identity, _):
        install_resource_security(app, identity)
        with pytest.raises(ValueError):
            async with app.router.lifespan_context(app):
                pytest.fail("不合规路由不能启动")
    assert entered == []


async def test_included_router_is_validated_and_lifespan_preserved(tmp_path):
    entered = []

    @asynccontextmanager
    async def lifespan(app):
        entered.append("start")
        yield {"custom": "preserved"}
        entered.append("stop")

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    router = APIRouter(prefix="/api/v1")

    @router.get(
        "/included", dependencies=[Depends(require_permission("documents.read", "document:read"))]
    )
    async def included():
        return {"subject": current_principal().subject}

    app.include_router(router)
    async with introspector(tmp_path) as (identity, _):
        install_resource_security(app, identity)
        async with (
            app.router.lifespan_context(app) as state,
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as http,
        ):
            assert state == {"custom": "preserved"}
            assert (
                await http.get("/api/v1/included", headers={"Authorization": "Bearer user-token"})
            ).json() == {"subject": USER}
    assert entered == ["start", "stop"]


@pytest.mark.parametrize("path,permission", [("/api/unguarded", False), ("/outside", True)])
async def test_nested_included_router_cannot_hide_unguarded_or_outside_route(
    tmp_path, path, permission
):
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    child = APIRouter()

    async def endpoint():
        return {}

    child.get(path)(endpoint)
    parent = APIRouter()
    parent.include_router(
        child,
        dependencies=[Depends(require_permission("documents.read", "document:read"))]
        if permission
        else [],
    )
    app.include_router(parent)
    async with introspector(tmp_path) as (identity, _):
        install_resource_security(app, identity)
        with pytest.raises(ValueError):
            async with app.router.lifespan_context(app):
                pytest.fail("嵌套路由不能绕过检查")


@pytest.mark.parametrize("response", ["inactive", "active", "error"])
async def test_readiness_discovers_before_authenticated_introspection_and_fails_closed(
    tmp_path, response
):
    order = []

    class Runtime:
        async def watch(self, service):
            order.append(service)

    secret = tmp_path / "startup.password"
    secret.write_text("fixture-only", encoding="utf-8")
    discovery = ServiceDiscovery(Provider())
    await discovery.refresh("identity-service")

    def handle(request):
        order.append("introspect")
        assert request.headers["authorization"].startswith("Basic ")
        return httpx.Response(
            503 if response == "error" else 200,
            json=claims("invalid" if response == "inactive" else "user-token"),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        identity = ResourceIntrospector(
            discovery,
            http,
            ResourceIdentitySettings(
                "local", FIXTURE["issuer"], FIXTURE["audience"], "reader", secret
            ),
        )
        if response == "inactive":
            await resource_identity_readiness(Runtime(), identity)
        else:
            with pytest.raises(PlatformError):
                await resource_identity_readiness(Runtime(), identity)
    assert order == ["identity-service", "introspect"]


@pytest.mark.parametrize(
    "kwargs",
    [
        {"scope": "bad scope", "permission": "document:read"},
        {"scope": "documents.read", "permission": "document:read", "principal_type": "anonymous"},
        {
            "scope": "documents.read",
            "permission": "document:read",
            "service_scope": "internal.forward",
        },
    ],
)
def test_invalid_permission_declaration_rejected(kwargs):
    with pytest.raises(ValueError):
        require_permission(**kwargs)
