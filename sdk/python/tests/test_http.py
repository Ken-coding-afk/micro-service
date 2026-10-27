import asyncio
import time
import uuid

import httpx
import pytest

from ken_platform_sdk.config import HttpParameters
from ken_platform_sdk.context import request_context
from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
from ken_platform_sdk.errors import DeadlineExceeded
from ken_platform_sdk.http import ServiceHttpClient


class Provider:
    async def query(self, service):
        return [ServiceInstance("first.example", 8001), ServiceInstance("second.example", 8002)]


async def test_get_has_only_one_extra_attempt_and_preserves_trusted_context():
    requests = []

    def handler(request):
        requests.append(request)
        if len(requests) == 1:
            raise httpx.ConnectError("offline", request=request)
        return httpx.Response(200, json={"ok": True})

    directory = ServiceDiscovery(Provider())
    await directory.refresh("analysis")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        http = ServiceHttpClient(directory, client)
        with request_context(
            request_id="request-1",
            tenant_id=uuid.uuid4(),
            principal_id="u1",
            access_token="verified-fixture",
            deadline=time.time() + 1,
        ):
            response = await http.request("analysis", "GET", "/api/v1/result")
    assert response.status_code == 200
    assert len(requests) == 2
    assert requests[0].url.host != requests[1].url.host
    assert requests[1].headers["Authorization"] == "Bearer verified-fixture"
    assert "X-Tenant-Id" not in requests[1].headers
    assert requests[1].headers["X-Request-Id"] == "request-1"


async def test_post_response_timeout_never_replays_committed_side_effect():
    commits = []

    async def handler(request):
        commits.append(request.content)
        await asyncio.Event().wait()

    directory = ServiceDiscovery(Provider())
    await directory.refresh("analysis")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        http = ServiceHttpClient(directory, client, HttpParameters(response_budget_seconds=0.02))
        with pytest.raises(DeadlineExceeded):
            await http.request("analysis", "POST", "/api/v1/jobs", json={"operation": 1})
    assert len(commits) == 1


async def test_expired_upstream_budget_sends_no_request():
    def handler(request):
        pytest.fail("过期预算不得发出请求")

    directory = ServiceDiscovery(Provider())
    await directory.refresh("analysis")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with request_context(deadline=time.time() - 1), pytest.raises(DeadlineExceeded):
            await ServiceHttpClient(directory, client).request("analysis", "GET", "/result")


@pytest.mark.parametrize("path", ["https://other.example/private", "//other.example/private"])
async def test_arbitrary_target_url_rejected(path):
    directory = ServiceDiscovery(Provider())
    async with httpx.AsyncClient() as client:
        with pytest.raises(ValueError):
            await ServiceHttpClient(directory, client).request("analysis", "GET", path)
