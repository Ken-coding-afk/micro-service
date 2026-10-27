"""应用技术生命周期：配置与检查完成后注册，先摘流再关闭客户端。"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable, Sequence

import httpx

from .config import PlatformSettings, RuntimeParameters, load_runtime
from .discovery import ServiceDiscovery
from .errors import DependencyUnavailable
from .http import ServiceHttpClient
from .nacos import NacosProvider

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

    async def start(self, checks: Sequence[Callable[[], Awaitable[None]]] = ()) -> None:
        try:
            await self.provider.open()
            self.parameters = load_runtime(
                await self.provider.get_config("application-shared.yaml"),
                await self.provider.get_config(f"{self.settings.service_name}.yaml"),
            )
            logging.getLogger().setLevel(self.parameters.log_level)
            for check in checks:
                await check()
            self._client = httpx.AsyncClient(follow_redirects=False)
            self.http = ServiceHttpClient(self.discovery, self._client, self.parameters.http)
            await self.provider.watch_config(
                ["application-shared.yaml", f"{self.settings.service_name}.yaml"],
                self._refresh_config,
            )
            if self.settings.role == "api":
                self._registration_attempted = True
                await self.provider.register()
            self.ready = True
        except BaseException:
            try:
                await self.stop()
            except Exception:  # noqa: BLE001 - 清理失败不能覆盖原始启动错误
                logger.warning("启动失败后的资源清理未完全成功")
            raise

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
        ):
            logger.warning("候选配置改变重启字段，拒绝在线应用")
            return False
        self.parameters = candidate
        logging.getLogger().setLevel(candidate.log_level)
        return True
