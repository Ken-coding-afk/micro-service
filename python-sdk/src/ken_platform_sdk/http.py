"""逻辑服务 HTTP 客户端；总预算覆盖读取响应与一次受限 GET 重试。"""

from __future__ import annotations

import asyncio
import time
from urllib.parse import urlsplit

import httpx
from anyio import CapacityLimiter

from .concurrency import admission
from .config import ConcurrencyParameters, HttpParameters
from .context import current_context, request_deadline
from .discovery import ServiceDiscovery
from .errors import DeadlineExceeded, DependencyUnavailable, PlatformError
from .observation import ProtectionMetrics


class ServiceHttpClient:
    def __init__(
        self,
        discovery: ServiceDiscovery,
        client: httpx.AsyncClient,
        parameters: HttpParameters | None = None,
        concurrency: ConcurrencyParameters | None = None,
        metrics: ProtectionMetrics | None = None,
    ):
        self.metrics = metrics or ProtectionMetrics()
        self.discovery = discovery
        self.client = client
        self.parameters = parameters or HttpParameters()
        self._limits = {
            service: CapacityLimiter(limit)
            for service, limit in (concurrency or ConcurrencyParameters()).outbound_limits.items()
        }

        for service, limiter in self._limits.items():
            self.metrics.active.labels("http.outbound/" + service).set_function(
                lambda limiter=limiter: limiter.borrowed_tokens
            )

    async def request(
        self, service: str, method: str, path: str, *, json: object | None = None
    ) -> httpx.Response:
        if request_deadline(self.parameters.response_budget_seconds) <= time.time():
            raise DeadlineExceeded()
        with admission(
            self._limits.get(service), metrics=self.metrics, resource="http.outbound/" + service
        ):
            return await self._request(service, method, path, json=json)

    async def _request(
        self, service: str, method: str, path: str, *, json: object | None = None
    ) -> httpx.Response:
        if not path.startswith("/") or path.startswith("//") or urlsplit(path).netloc:
            raise ValueError("服务请求只允许所属服务的绝对路径，不能传任意目标 URL")
        method = method.upper()
        deadline = request_deadline(self.parameters.response_budget_seconds)
        monotonic_deadline = time.monotonic() + max(0, deadline - time.time())
        attempts = 1 + (self.parameters.get_retry_extra if method == "GET" else 0)
        context = current_context.get()
        headers = {"X-Request-Deadline": str(int(deadline * 1000))}
        if context:
            headers["X-Request-Id"] = context.request_id
            if context.access_token:
                headers["Authorization"] = f"Bearer {context.access_token}"
            if context.traceparent:
                headers["traceparent"] = context.traceparent
        for attempt in range(attempts):
            remaining = min(deadline - time.time(), monotonic_deadline - time.monotonic())
            if remaining <= 0:
                raise DeadlineExceeded()
            instance = self.discovery.choose(service)
            timeout = httpx.Timeout(
                remaining,
                connect=min(remaining, self.parameters.connect_timeout_seconds),
                pool=min(remaining, 1.0),
            )
            try:
                async with asyncio.timeout(remaining):
                    return await self.client.request(
                        method,
                        instance.origin + path,
                        json=json,
                        headers=headers,
                        timeout=timeout,
                        follow_redirects=False,
                    )
            except httpx.PoolTimeout:
                self.metrics.reject("http.outbound/pool", "pool_timeout")
                raise PlatformError("DEPENDENCY_BUSY", "业务连接池容量已满", 503) from None
            except asyncio.CancelledError:
                if self.metrics.enabled:
                    self.metrics.cancelled.labels("outbound").inc()
                raise
            except TimeoutError as exc:
                # 总预算可能耗在本地池等待，不能凭这一异常摘除健康实例。
                raise DeadlineExceeded() from exc
            except httpx.TimeoutException as exc:
                self.discovery.invalidate(service, instance)
                if attempt + 1 == attempts:
                    raise DeadlineExceeded() from exc
            except httpx.TransportError as exc:
                self.discovery.invalidate(service, instance)
                if attempt + 1 == attempts:
                    raise DependencyUnavailable() from exc
        raise DependencyUnavailable()
