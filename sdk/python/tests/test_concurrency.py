import asyncio
from types import SimpleNamespace

import httpx
import pytest
from anyio import CapacityLimiter

from ken_platform_sdk.config import ConcurrencyParameters, HttpParameters
from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.http import ServiceHttpClient
from ken_platform_sdk.observation import ProtectionMetrics
from ken_platform_sdk.web import PlatformMiddleware


class Provider:
    async def query(self, service):
        return [ServiceInstance("first.example", 8001)]


async def test_outbound_busy_cancel_and_pool_timeout_do_not_invalidate_or_retry():
    entered = asyncio.Event()
    release = asyncio.Event()
    calls = []

    async def handler(request):
        calls.append(request)
        entered.set()
        await release.wait()
        return httpx.Response(503)

    directory = ServiceDiscovery(Provider())
    await directory.refresh("analysis")
    limits = ConcurrencyParameters(outbound_limits={"analysis": 1})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as raw:
        client = ServiceHttpClient(directory, raw, concurrency=limits)
        pending = asyncio.create_task(client.request("analysis", "GET", "/value"))
        await entered.wait()
        with pytest.raises(PlatformError, match="容量") as busy:
            await client.request("analysis", "GET", "/value")
        assert busy.value.code == "DEPENDENCY_BUSY"
        assert len(calls) == 1
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        assert client._limits["analysis"].borrowed_tokens == 0
        assert directory.choose("analysis").host == "first.example"
        release.set()
        assert (await client.request("analysis", "GET", "/value")).status_code == 503
        assert len(calls) == 2

    calls.clear()

    def pool_timeout(request):
        calls.append(request)
        raise httpx.PoolTimeout("fixture")

    async with httpx.AsyncClient(transport=httpx.MockTransport(pool_timeout)) as raw:
        client = ServiceHttpClient(directory, raw, concurrency=limits)
        with pytest.raises(PlatformError) as error:
            await client.request("analysis", "GET", "/value")
        assert error.value.code == "DEPENDENCY_BUSY"
        assert len(calls) == 1 and client._limits["analysis"].borrowed_tokens == 0
        assert directory.choose("analysis").host == "first.example"


async def test_stream_holds_inbound_until_actual_completion_and_health_bypasses():
    entered = asyncio.Event()
    finish = asyncio.Event()

    async def app(scope, receive, send):
        await send({"type": "http.response.start", "status": 200, "headers": []})
        entered.set()
        if scope["path"].startswith("/api/"):
            await finish.wait()
        await send({"type": "http.response.body", "body": b"end"})

    runtime = SimpleNamespace(inbound=CapacityLimiter(1), metrics=ProtectionMetrics())
    middleware = PlatformMiddleware(app, runtime)

    async def receive():
        return {"type": "http.request", "body": b""}

    messages = []

    async def send(message):
        messages.append(message)

    scope = {"type": "http", "path": "/api/v1/stream", "headers": []}
    pending = asyncio.create_task(middleware(scope, receive, send))
    await entered.wait()
    assert runtime.inbound.borrowed_tokens == 1
    await middleware(scope, receive, send)
    assert any(m.get("status") == 503 for m in messages)
    await middleware({**scope, "path": "/actuator/health"}, receive, send)
    assert runtime.inbound.borrowed_tokens == 1
    finish.set()
    await pending
    assert runtime.inbound.borrowed_tokens == 0


async def test_sync_work_holds_capacity_through_anyio_cancellation_until_thread_returns():
    import threading

    import anyio

    entered, finish = threading.Event(), threading.Event()
    runtime = SimpleNamespace(inbound=CapacityLimiter(1), metrics=ProtectionMetrics())

    def work():
        entered.set()
        assert finish.wait(5)

    async def app(scope, receive, send):
        await anyio.to_thread.run_sync(work)

    async def receive():
        return {"type": "http.request", "body": b""}

    async def send(message):
        pass

    middleware = PlatformMiddleware(app, runtime)
    async with anyio.create_task_group() as group:
        group.start_soon(
            middleware, {"type": "http", "path": "/api/v1/items", "headers": []}, receive, send
        )
        await anyio.to_thread.run_sync(entered.wait, 2)
        assert entered.is_set() and runtime.inbound.borrowed_tokens == 1
        group.cancel_scope.cancel()
        with anyio.CancelScope(shield=True):
            await anyio.lowlevel.checkpoint()
            assert runtime.inbound.borrowed_tokens == 1
            finish.set()
    assert runtime.inbound.borrowed_tokens == 0


@pytest.mark.parametrize("value", [0, -1, True, "2"])
def test_invalid_concurrency_limits_rejected(value):
    with pytest.raises(ValueError):
        ConcurrencyParameters(inbound_limit=value)
    with pytest.raises(ValueError):
        ConcurrencyParameters(outbound_limits={"analysis": value})


def test_pool_bounds_are_validated():
    with pytest.raises(ValueError):
        HttpParameters(max_connections=1, max_keepalive_connections=2)


def _capacity_worker(connection):
    import anyio

    from ken_platform_sdk.concurrency import admission

    async def run():
        limiter = CapacityLimiter(2)
        with admission(limiter), admission(limiter):
            try:
                with admission(limiter):
                    raise AssertionError("third request admitted")
            except PlatformError:
                connection.send(limiter.borrowed_tokens)
            connection.recv()
        connection.send(limiter.borrowed_tokens)

    anyio.run(run)


def test_two_os_workers_each_hold_their_own_capacity():
    import multiprocessing

    context = multiprocessing.get_context("spawn")
    workers = []
    try:
        for _ in range(2):
            parent, child = context.Pipe()
            process = context.Process(target=_capacity_worker, args=(child,))
            process.start()
            workers.append((process, parent))
        held = []
        for _, pipe in workers:
            assert pipe.poll(10)
            held.append(pipe.recv())
        assert held == [2, 2]  # 同时 4 个：上限属于进程，不能宣称全局只有 2 个。
        for process, pipe in workers:
            pipe.send("release")
            assert pipe.poll(10) and pipe.recv() == 0
            process.join(5)
            assert process.exitcode == 0
    finally:
        for process, pipe in workers:
            if process.is_alive():
                process.terminate()
            process.join(5)
            pipe.close()
