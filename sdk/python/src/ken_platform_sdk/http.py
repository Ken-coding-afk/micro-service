"""逻辑服务 HTTP 客户端；总预算覆盖读取响应与一次受限 GET 重试。"""

from __future__ import annotations

import asyncio
import re
import time
from typing import TypeVar
from urllib.parse import urlsplit

import httpx
from anyio import CapacityLimiter
from pydantic import TypeAdapter, ValidationError

from .circuit import DependencyCircuit, RemoteFailure
from .concurrency import admission
from .config import ConcurrencyParameters, HttpParameters
from .context import current_context, request_deadline
from .discovery import ServiceDiscovery
from .errors import DeadlineExceeded, DependencyUnavailable, PlatformError
from .observation import ProtectionMetrics

T = TypeVar("T")


class ServiceHttpClient:
    async def request_model(
        self,
        service: str,
        method: str,
        path: str,
        response_type: type[T],
        *,
        json: object | None = None,
    ) -> T:
        """复用 raw 传输；DTO 必填/可空规则由业务的 Pydantic 模型声明。"""
        remaining = request_deadline(self.parameters.response_budget_seconds) - time.time()
        if remaining <= 0:
            raise DeadlineExceeded()
        started = time.monotonic()
        try:
            async with asyncio.timeout(remaining):
                response = await self.request(service, method, path, json=json)
                if not response.is_success:
                    code = "DEPENDENCY_REJECTED"
                    try:
                        value = response.json() if len(response.content) <= 65536 else {}
                        candidate = value.get("code") if isinstance(value, dict) else None
                        if isinstance(candidate, str) and re.fullmatch(
                            r"[A-Z][A-Z0-9_]{0,63}", candidate
                        ):
                            code = candidate
                    except ValueError:
                        pass
                    raise PlatformError(code, "依赖未返回成功响应", response.status_code)
                if response.status_code == 204 and response_type is type(None):
                    return None  # type: ignore[return-value]
                try:
                    result = TypeAdapter(response_type).validate_json(response.content, strict=True)
                except (ValidationError, ValueError, TypeError):
                    raise PlatformError(
                        "DEPENDENCY_INVALID_RESPONSE", "依赖响应不符合声明的类型", 502
                    ) from None
                if time.monotonic() - started >= remaining:
                    raise DeadlineExceeded()
                return result
        except TimeoutError:
            raise DeadlineExceeded() from None

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
        self._circuits = {
            name: DependencyCircuit(name, rule, self.metrics)
            for name, rule in self.parameters.circuit_breakers.items()
            if rule.enabled
        }
        for name, circuit in self._circuits.items():
            self.metrics.circuit.labels(name).set_function(
                lambda circuit=circuit: {"closed": 0, "open": 1, "half_open": 2}[circuit.state]
            )
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
            circuit = self._circuits.get(service)
            if circuit is None:
                return await self._request(service, method, path, json=json)
            response = None
            failure = None

            async def call():
                nonlocal response, failure
                # 发现失败属于本地准入异常；开路时不应查询实例覆盖熔断错误。
                self.discovery.choose(service)
                try:
                    response = await self._request(service, method, path, json=json)
                except (DependencyUnavailable, DeadlineExceeded) as error:
                    if not isinstance(
                        error.__cause__, (httpx.TransportError, httpx.TimeoutException)
                    ):
                        raise
                    failure = error
                    raise RemoteFailure() from None
                if response.status_code >= 500:
                    raise RemoteFailure()
                return response

            try:
                return await circuit.execute(call)
            except RemoteFailure:
                if failure is not None:
                    raise failure from None
                assert response is not None
                return response  # raw HTTP 接口仍交付原 5xx，类型化入口统一解释。

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
