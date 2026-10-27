import asyncio

import circuitbreaker
import pytest

from ken_platform_sdk.circuit import DependencyCircuit, RemoteFailure
from ken_platform_sdk.config import CircuitParameters
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.observation import ProtectionMetrics


async def ok():
    return 1


async def failed():
    raise RemoteFailure()


async def test_open_reject_and_single_half_open_probe(monkeypatch):
    clock = [10.0]
    monkeypatch.setattr(circuitbreaker, "monotonic", lambda: clock[0])
    breaker = DependencyCircuit(
        "peer",
        CircuitParameters(enabled=True, failure_threshold=2, recovery_timeout_seconds=5),
        ProtectionMetrics(),
    )
    for _ in range(2):
        with pytest.raises(RemoteFailure):
            await breaker.execute(failed)
    with pytest.raises(PlatformError) as error:
        await breaker.execute(ok)
    assert error.value.code == "DEPENDENCY_CIRCUIT_OPEN"
    clock[0] += 6
    entered, release = asyncio.Event(), asyncio.Event()

    async def probe():
        entered.set()
        await release.wait()
        return 7

    task = asyncio.create_task(breaker.execute(probe))
    await entered.wait()
    with pytest.raises(PlatformError):
        await breaker.execute(ok)
    release.set()
    assert await task == 7
    assert breaker.state == "closed"


async def test_cancel_and_local_error_do_not_reset_failure_count():
    breaker = DependencyCircuit(
        "peer", CircuitParameters(enabled=True, failure_threshold=2), ProtectionMetrics()
    )
    with pytest.raises(RemoteFailure):
        await breaker.execute(failed)
    for error in [asyncio.CancelledError(), ValueError("local")]:

        async def neutral(error=error):
            raise error

        with pytest.raises(type(error)):
            await breaker.execute(neutral)
        assert breaker.failure_count == 1
    with pytest.raises(RemoteFailure):
        await breaker.execute(failed)
    assert breaker.opened


async def test_old_inflight_success_does_not_close_newly_open_circuit():
    breaker = DependencyCircuit(
        "peer", CircuitParameters(enabled=True, failure_threshold=1), ProtectionMetrics()
    )
    entered, release = asyncio.Event(), asyncio.Event()

    async def slow():
        entered.set()
        await release.wait()

    task = asyncio.create_task(breaker.execute(slow))
    await entered.wait()
    with pytest.raises(RemoteFailure):
        await breaker.execute(failed)
    release.set()
    await task
    assert breaker.opened


async def test_http_retry_counts_once_and_open_precedes_empty_discovery():
    import httpx

    from ken_platform_sdk.config import HttpParameters
    from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
    from ken_platform_sdk.errors import DependencyUnavailable
    from ken_platform_sdk.http import ServiceHttpClient

    class Provider:
        async def query(self, service):
            return [ServiceInstance("one.invalid", 80), ServiceInstance("two.invalid", 80)]

    discovery = ServiceDiscovery(Provider())
    await discovery.refresh("peer")
    calls = []

    def handler(request):
        calls.append(request)
        raise httpx.ConnectError("disconnected", request=request)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as transport:
        client = ServiceHttpClient(
            discovery,
            transport,
            HttpParameters(
                circuit_breakers={"peer": CircuitParameters(enabled=True, failure_threshold=1)}
            ),
        )
        with pytest.raises(DependencyUnavailable):
            await client.request("peer", "GET", "/items")
        assert len(calls) == 2
        assert client._circuits["peer"].failure_count == 1
        with pytest.raises(PlatformError) as error:
            await client.request("peer", "GET", "/items")
        assert error.value.code == "DEPENDENCY_CIRCUIT_OPEN"
        assert len(calls) == 2
