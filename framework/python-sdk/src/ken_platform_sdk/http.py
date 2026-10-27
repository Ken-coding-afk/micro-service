"""逻辑服务 HTTP 客户端；总预算覆盖读取响应与一次受限 GET 重试。"""

from __future__ import annotations

import asyncio
import time
from urllib.parse import urlsplit

import httpx

from .config import HttpParameters
from .context import current_context, request_deadline
from .discovery import ServiceDiscovery
from .errors import DeadlineExceeded, DependencyUnavailable


class ServiceHttpClient:
    def __init__(
        self,
        discovery: ServiceDiscovery,
        client: httpx.AsyncClient,
        parameters: HttpParameters | None = None,
    ):
        self.discovery = discovery
        self.client = client
        self.parameters = parameters or HttpParameters()

    async def request(
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
                remaining, connect=min(remaining, self.parameters.connect_timeout_seconds)
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
            except (TimeoutError, httpx.TimeoutException) as exc:
                self.discovery.invalidate(service, instance)
                if attempt + 1 == attempts:
                    raise DeadlineExceeded() from exc
            except httpx.TransportError as exc:
                self.discovery.invalidate(service, instance)
                if attempt + 1 == attempts:
                    raise DependencyUnavailable() from exc
        raise DependencyUnavailable()
