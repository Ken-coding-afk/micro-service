"""应用技术生命周期：配置与检查完成后注册，先摘流再关闭客户端。"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable, Sequence

import httpx
from anyio import CapacityLimiter

from .config import PlatformSettings, RuntimeParameters, load_runtime
from .discovery import ServiceDiscovery
from .errors import DependencyUnavailable
from .http import ServiceHttpClient
from .log_context import configure_logging
from .nacos import NacosProvider
from .observation import ProtectionMetrics

logger = logging.getLogger(__name__)


class PlatformRuntime:
    def __init__(self, settings: PlatformSettings, provider: NacosProvider | None = None):
        self.settings = settings
        self.provider = provider or NacosProvider(settings)
        self.discovery = ServiceDiscovery(self.provider)
        self.parameters: RuntimeParameters | None = None
        self.ready = False
        self._registration_attempted = False
        self._client: httpx.AsyncClient | None = None
        self.http: ServiceHttpClient | None = None
        self.inbound: CapacityLimiter | None = None
        self.metrics = ProtectionMetrics()
        self._readiness_checks: Sequence[Callable[[], Awaitable[bool]]] = ()

    def install(self, app) -> None:
        """在构建 FastAPI 应用时装配一次；参数在 start 校验后生效。"""
        from .web import PlatformMiddleware, install_error_handlers

        if getattr(app.state, "platform_runtime", None) is not None:
            raise ValueError("运行时不能重复安装")
        if any(m.cls is PlatformMiddleware for m in app.user_middleware):
            raise ValueError("必须先安装 runtime，再安装资源安全")
        app.add_middleware(PlatformMiddleware, runtime=self)
        install_error_handlers(app)
        app.state.platform_runtime = self

    async def start(
        self,
        checks: Sequence[Callable[[], Awaitable[None]]] = (),
        *,
        readiness_checks: Sequence[Callable[[], Awaitable[bool]]] = (),
    ) -> None:
        try:
            configure_logging(self.settings.service_name)
            self._readiness_checks = tuple(readiness_checks)
            await self.provider.open()
            self.parameters = load_runtime(
                await self.provider.get_config("application-shared.yaml"),
                await self.provider.get_config(f"{self.settings.service_name}.yaml"),
            )
            logging.getLogger().setLevel(self.parameters.log_level)
            for check in checks:
                await check()
            if not await self._dependencies_ready():
                raise DependencyUnavailable("启动前依赖检查失败")
            limits = self.parameters.concurrency
            self.inbound = CapacityLimiter(limits.inbound_limit) if limits.inbound_limit else None
            self.metrics.enabled = self.parameters.observation.metrics_enabled
            if self.inbound:
                self.metrics.active.labels("http.inbound").set_function(
                    lambda: self.inbound.borrowed_tokens if self.inbound else 0
                )
            self._client = httpx.AsyncClient(
                follow_redirects=False,
                limits=httpx.Limits(
                    max_connections=self.parameters.http.max_connections,
                    max_keepalive_connections=self.parameters.http.max_keepalive_connections,
                ),
            )
            self.http = ServiceHttpClient(
                self.discovery, self._client, self.parameters.http, limits, self.metrics
            )
            await self.provider.watch_config(
                ["application-shared.yaml", f"{self.settings.service_name}.yaml"],
                self._refresh_config,
            )
            if self.settings.role == "api":
                self._registration_attempted = True
                await self.provider.register()
            self.metrics.start(self.settings.metrics_bind_host, self.settings.metrics_port)
            self.ready = True
        except BaseException:
            try:
                await self.stop()
            except Exception:  # noqa: BLE001 - 清理失败不能覆盖原始启动错误
                logger.warning("启动失败后的资源清理未完全成功")
            raise

    async def _dependencies_ready(self) -> bool:
        try:
            async with asyncio.timeout(2):
                for check in self._readiness_checks:
                    if await check() is not True:
                        return False
            return True
        except Exception:  # noqa: BLE001 - 依赖/探测回调任何失败都不能报告就绪
            return False

    async def is_ready(self) -> bool:
        """不重跑装配，不缓存成功探测；依赖恢复后下一次探测重新判断。"""
        return self.ready and await self._dependencies_ready() and self.ready

    async def _refresh_config(self) -> None:
        try:
            self.apply_hot_update(
                await self.provider.get_config("application-shared.yaml"),
                await self.provider.get_config(f"{self.settings.service_name}.yaml"),
            )
        except DependencyUnavailable:
            logger.warning("配置源暂不可达，保留已验证配置")

    async def watch(self, service: str) -> None:
        await self.discovery.watch(service)
        await self.provider.subscribe(service, lambda: self.discovery.refresh(service))

    async def stop(self) -> None:
        registered, self._registration_attempted = self._registration_attempted, False
        self.ready = False
        self.metrics.stop()
        try:
            if registered and self.settings.role == "api":
                await self.provider.deregister()
        finally:
            await self.discovery.close()
            try:
                if self._client is not None:
                    await self._client.aclose()
                    self._client = None
                    self.http = None
            finally:
                await self.provider.close()

    def apply_hot_update(self, shared: str, service: str) -> bool:
        """只支持日志/指标更新；HTTP 池/预算等变化需要重启，失败保留旧快照。"""
        try:
            candidate = load_runtime(shared, service)
        except ValueError:
            logger.warning("候选配置快照不合法，保留已验证配置")
            return False
        if (
            self.parameters is None
            or candidate.http != self.parameters.http
            or candidate.business != self.parameters.business
            or candidate.concurrency != self.parameters.concurrency
        ):
            logger.warning("候选配置改变重启字段，拒绝在线应用")
            return False
        self.parameters = candidate
        self.metrics.enabled = candidate.observation.metrics_enabled
        logging.getLogger().setLevel(candidate.log_level)
        return True
