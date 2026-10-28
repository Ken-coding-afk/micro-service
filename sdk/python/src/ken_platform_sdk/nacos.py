"""固定 Nacos 3.2 SDK 适配；在此边界强制网络确认，禁止延长 SDK 缓存寿命。"""

from __future__ import annotations

import asyncio
import logging
import tempfile
from collections.abc import Awaitable, Callable

from grpc import RpcError
from v2.nacos import (
    ClientConfigBuilder,
    ConfigParam,
    DeregisterInstanceParam,
    NacosConfigService,
    NacosNamingService,
    RegisterInstanceParam,
    SubscribeServiceParam,
)
from v2.nacos.common.nacos_exception import NacosException

from .config import PlatformSettings
from .discovery import ServiceInstance
from .errors import DependencyUnavailable


class NacosProvider:
    def __init__(self, settings: PlatformSettings):
        self.settings = settings
        self.naming: NacosNamingService | None = None
        self.config: NacosConfigService | None = None
        self._subscriptions: dict[str, SubscribeServiceParam] = {}
        self._config_listeners: dict[str, Callable] = {}
        self._cache: tempfile.TemporaryDirectory | None = None

    async def open(self) -> None:
        self.settings.nacos_log_dir.mkdir(parents=True, exist_ok=True)
        self._cache = tempfile.TemporaryDirectory(
            prefix="runtime-cache-", dir=self.settings.nacos_log_dir.resolve()
        )
        client_config = (
            ClientConfigBuilder()
            .server_address(self.settings.nacos_server_addr)
            .namespace_id(self.settings.nacos_namespace)
            .username(self.settings.nacos_runtime_user)
            .password(self.settings.nacos_password.get_secret_value())
            .log_level(logging.WARNING)
            .log_dir(str(self.settings.nacos_log_dir.resolve()))
            .cache_dir(self._cache.name)
            .load_cache_at_start(False)
            .async_update_service(False)
            .build()
        )
        client_config.disable_use_config_cache = True
        try:
            self.config = await asyncio.wait_for(
                NacosConfigService.create_config_service(client_config), 10
            )
            self.naming = await asyncio.wait_for(
                NacosNamingService.create_naming_service(client_config), 10
            )
        except (NacosException, RpcError, TimeoutError, OSError, ValueError):
            await self.close()
            raise DependencyUnavailable("Nacos 首次连接失败，应用不能就绪") from None

    async def get_config(self, data_id: str) -> str:
        if self.config is None:
            raise DependencyUnavailable()
        try:
            return await asyncio.wait_for(
                self.config.get_config(ConfigParam(data_id=data_id, group="DEFAULT_GROUP")), 10
            )
        except (NacosException, RpcError, TimeoutError, OSError, ValueError):
            raise DependencyUnavailable("必需配置无法读取") from None

    async def query(self, service: str) -> list[ServiceInstance]:
        if self.naming is None:
            raise DependencyUnavailable()
        try:
            # 3.2.0 的 list_instances(subscribe=False) 仍会经 proxy.subscribe 命中缓存。
            # 此固定版本适配只使用直接发 gRPC 请求的 do_subscribe；升级必须跑原生探针。
            confirmed = await asyncio.wait_for(
                self.naming.grpc_client_proxy.do_subscribe(service, "DEFAULT_GROUP", ""),
                10,
            )
            if confirmed is None:
                raise DependencyUnavailable("服务目录网络确认失败")
            return [
                ServiceInstance(instance.ip, instance.port, instance.metadata.get("scheme", "http"))
                for instance in confirmed.hosts
                if instance.healthy
                and instance.enabled
                and instance.weight > 0
                and instance.metadata.get("scheme", "http") in ("http", "https")
            ]
        except (NacosException, RpcError, TimeoutError, OSError, ValueError):
            raise DependencyUnavailable("服务目录网络确认失败") from None

    async def subscribe(
        self, service: str, confirmed_refresh: Callable[[], Awaitable[None]]
    ) -> None:
        if self.naming is None:
            raise DependencyUnavailable()
        if service in self._subscriptions:
            return

        async def changed(*args):
            try:
                await confirmed_refresh()
            except DependencyUnavailable:
                # SDK 缓存通知不能延长最后一次网络确认的有效期。
                pass

        request = SubscribeServiceParam(
            service_name=service, group_name="DEFAULT_GROUP", subscribe_callback=changed
        )
        await self.naming.subscribe(request)
        self._subscriptions[service] = request

    async def watch_config(
        self, data_ids: list[str], refresh: Callable[[], Awaitable[None]]
    ) -> None:
        if self.config is None:
            raise DependencyUnavailable()

        async def changed(*args):
            await refresh()

        for data_id in data_ids:
            if data_id not in self._config_listeners:
                await self.config.add_listener(data_id, "DEFAULT_GROUP", changed)
                self._config_listeners[data_id] = changed

    async def register(self) -> None:
        if self.naming is None:
            raise DependencyUnavailable()
        success = await asyncio.wait_for(
            self.naming.register_instance(
                RegisterInstanceParam(
                    service_name=self.settings.service_name,
                    group_name="DEFAULT_GROUP",
                    ip=self.settings.service_advertise_host,
                    port=self.settings.service_advertise_port,
                    metadata={"role": "api", "scheme": self.settings.service_scheme},
                    ephemeral=True,
                )
            ),
            10,
        )
        if not success:
            raise DependencyUnavailable("API 实例注册失败")

    async def deregister(self) -> None:
        if self.naming is not None:
            await asyncio.wait_for(
                self.naming.deregister_instance(
                    DeregisterInstanceParam(
                        service_name=self.settings.service_name,
                        group_name="DEFAULT_GROUP",
                        ip=self.settings.service_advertise_host,
                        port=self.settings.service_advertise_port,
                        ephemeral=True,
                    )
                ),
                10,
            )

    async def close(self) -> None:
        naming, self.naming = self.naming, None
        config, self.config = self.config, None
        if naming is not None:
            await asyncio.gather(
                *(naming.unsubscribe(request) for request in self._subscriptions.values()),
                return_exceptions=True,
            )
        self._subscriptions.clear()
        if config is not None:
            await asyncio.gather(
                *(
                    config.remove_listener(data_id, "DEFAULT_GROUP", callback)
                    for data_id, callback in self._config_listeners.items()
                ),
                return_exceptions=True,
            )
        self._config_listeners.clear()
        closing: list[Awaitable[None]] = []
        if naming is not None:
            closing.append(naming.shutdown())
        if config is not None:
            closing.append(config.shutdown())
        results = await asyncio.gather(*closing, return_exceptions=True)
        if any(isinstance(result, BaseException) for result in results):
            logging.getLogger(__name__).warning("部分 Nacos 客户端退出失败，请核对实例摘除")
        if self._cache is not None:
            self._cache.cleanup()
            self._cache = None
