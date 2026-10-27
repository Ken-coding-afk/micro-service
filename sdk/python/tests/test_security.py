"""两语言共用内省合同；HTTPX transport 夹具不是身份服务真实验收。"""

import asyncio
import base64
import copy
import json
import time
from pathlib import Path
from urllib.parse import parse_qs

import httpx
import pytest

from ken_platform_sdk.config import HttpParameters
from ken_platform_sdk.context import current_context, request_context
from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.security import (
    DataScope,
    ResourceIdentitySettings,
    ResourceIntrospector,
    parse_principal,
)

FIXTURE = json.loads(
    (Path(__file__).resolve().parents[3] / "tests/fixtures/introspection-v1.json").read_text(
        encoding="utf-8"
    )
)
assert FIXTURE["test_only"] is True


@pytest.mark.parametrize("case", FIXTURE["cases"], ids=lambda case: case["name"])
def test_shared_cross_language_claim_contract(case):
    if case["status"] == 200:
        principal = parse_principal(
            case["claims"], FIXTURE["issuer"], FIXTURE["audience"], now=FIXTURE["now"]
        )
        assert principal.subject == case["claims"]["sub"]
    else:
        with pytest.raises(PlatformError) as failure:
            parse_principal(
                case["claims"], FIXTURE["issuer"], FIXTURE["audience"], now=FIXTURE["now"]
            )
        assert failure.value.status == case["status"]
        assert failure.value.__cause__ is None


def test_scope_permission_type_and_object_range_have_independent_checks():
    principal = parse_principal(
        FIXTURE["cases"][0]["claims"], FIXTURE["issuer"], FIXTURE["audience"], now=FIXTURE["now"]
    )
    principal.require("documents.read", "document:read", "user")
    assert principal.data_scope("document:read") == DataScope.SELF
    assert str(principal.require_tenant()) == FIXTURE["cases"][0]["claims"]["tenant_id"]
    for scope, permission, kind in [
        ("documents.write", "document:read", "user"),
        ("documents.read", "document:write", "user"),
        ("documents.read", "document:read", "service"),
    ]:
        with pytest.raises(PlatformError) as failure:
            principal.require(scope, permission, kind)
        assert failure.value.status == 403
    with pytest.raises(PlatformError) as failure:
        principal.data_scope("document:write")
    assert failure.value.status == 403
    with pytest.raises(TypeError):
        principal.data_scopes["document:read"] = DataScope.TENANT


def settings(tmp_path):
    secret = tmp_path / "introspection.password"
    secret.write_text("private-fixture:+ /\n", encoding="utf-8")
    return ResourceIdentitySettings(
        "local", FIXTURE["issuer"], FIXTURE["audience"], "reader:document", secret
    )


class Provider:
    async def query(self, service):
        assert service == "identity-service"
        return [ServiceInstance("127.0.0.1", 18081)]


async def discovery():
    result = ServiceDiscovery(Provider())
    await result.refresh("identity-service")
    return result


def live_claims():
    result = copy.deepcopy(FIXTURE["cases"][0]["claims"])
    result["exp"] = int(time.time()) + 600
    return result


async def test_each_call_has_own_basic_form_and_no_identity_headers_or_success_cache(tmp_path):
    requests = []
    active = True

    def handle(request):
        requests.append(request)
        assert request.method == "POST"
        assert str(request.url) == "http://127.0.0.1:18081/oauth2/introspect"
        assert (
            request.headers["authorization"]
            == "Basic " + base64.b64encode(b"reader%3Adocument:private-fixture%3A%2B+%2F").decode()
        )
        assert parse_qs(request.content.decode()) == {"token": ["fixture-token"]}
        assert "x-tenant-id" not in request.headers and "x-user-id" not in request.headers
        return httpx.Response(200, json=live_claims() if active else {"active": False})

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handle),
        auth=("wrong-default", "wrong-default"),
        headers={"Authorization": "Bearer DO_NOT_FORWARD"},
        follow_redirects=True,
    ) as http:
        client = ResourceIntrospector(await discovery(), http, settings(tmp_path))
        with request_context(request_id="introspect-read-1", access_token="DO_NOT_FORWARD"):
            principal = await client.introspect("fixture-token")
            assert principal.permissions == {"document:read"}
            assert requests[0].headers["x-request-id"] == "introspect-read-1"
            active = False
            with pytest.raises(PlatformError) as failure:
                await client.introspect("fixture-token")
            assert failure.value.status == 401
        assert current_context.get() is None
        assert len(requests) == 2


@pytest.mark.parametrize("status", [200, 302, 401, 403, 500, 503])
async def test_malformed_and_error_responses_never_become_authenticated_success(tmp_path, status):
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(
            status, text="DO_NOT_ECHO_UPSTREAM", headers={"Location": "/redirect"}
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handle), follow_redirects=True
    ) as http:
        client = ResourceIntrospector(await discovery(), http, settings(tmp_path))
        with pytest.raises(PlatformError) as failure:
            await client.introspect("fixture-token")
        assert failure.value.status == 503 and failure.value.code == "IDENTITY_UNAVAILABLE"
        assert "DO_NOT_ECHO" not in str(failure.value)
        assert failure.value.__cause__ is None
        assert len(requests) == 1


async def test_invalid_bearer_expired_budget_or_stale_discovery_makes_no_request(tmp_path):
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json=live_claims())

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        directory = await discovery()
        client = ResourceIntrospector(directory, http, settings(tmp_path))
        with pytest.raises(PlatformError) as failure:
            await client.introspect("not a valid\nBearer")
        assert failure.value.status == 401
        with request_context(deadline=time.time() - 1):
            with pytest.raises(PlatformError) as failure:
                await client.introspect("fixture-token")
            assert failure.value.status == 503
        directory.clock = lambda: time.monotonic() + 61
        with pytest.raises(PlatformError) as failure:
            await client.introspect("fixture-token")
        assert failure.value.status == 503
        assert requests == []


async def test_budget_includes_response_and_post_is_not_retried(tmp_path):
    requests = []

    async def handle(request):
        requests.append(request)
        await asyncio.sleep(0.5)
        return httpx.Response(200, json=live_claims())

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        client = ResourceIntrospector(
            await discovery(),
            http,
            settings(tmp_path),
            HttpParameters(connect_timeout_seconds=0.05, response_budget_seconds=0.05),
        )
        started = time.monotonic()
        with pytest.raises(PlatformError) as failure:
            await client.introspect("fixture-token")
        assert failure.value.status == 503
        assert time.monotonic() - started < 2
        assert len(requests) == 1


@pytest.mark.parametrize(
    "scenario",
    [
        "issuer",
        "path",
        "port",
        "environment",
        "audience",
        "client",
        "relative",
        "missing",
        "empty",
        "literal",
    ],
)
def test_bad_binding_and_plain_secret_second_source_fail_without_echo(tmp_path, scenario):
    value = settings(tmp_path)
    source = {
        "APP_ENV": value.app_env,
        "PUBLIC_ORIGIN": value.issuer,
        "RESOURCE_AUDIENCE": value.audience,
        "INTROSPECTION_CLIENT_ID": value.client_id,
        "INTROSPECTION_CLIENT_SECRET_FILE": str(value.secret_file),
    }
    changes = {
        "issuer": ("PUBLIC_ORIGIN", "http://untrusted.example"),
        "path": ("PUBLIC_ORIGIN", "https://valid.example/private"),
        "port": ("PUBLIC_ORIGIN", "https://valid.example:65536"),
        "environment": ("APP_ENV", "production"),
        "audience": ("RESOURCE_AUDIENCE", "*"),
        "client": ("INTROSPECTION_CLIENT_ID", "reader\r\nother"),
        "relative": ("INTROSPECTION_CLIENT_SECRET_FILE", "password"),
        "missing": ("INTROSPECTION_CLIENT_SECRET_FILE", str(tmp_path / "missing")),
        "literal": ("INTROSPECTION_CLIENT_SECRET", "DO_NOT_ECHO"),
    }
    if scenario == "empty":
        value.secret_file.write_text(" ", encoding="utf-8")
    else:
        key, update = changes[scenario]
        source[key] = update
    with pytest.raises(ValueError) as failure:
        ResourceIdentitySettings.from_environment(source)
    assert failure.value.__cause__ is None
    assert "DO_NOT_ECHO" not in str(failure.value)
    assert "private-fixture" not in repr(value)
