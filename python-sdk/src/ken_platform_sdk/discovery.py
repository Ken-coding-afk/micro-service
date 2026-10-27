"""有界服务发现快照；失败、SDK 缓存读取均不能刷新确认时间。"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal, Protocol
from urllib.parse import urlsplit

from .errors import DependencyUnavailable


@dataclass(frozen=True)
class ServiceInstance:
    host: str
    port: int
    scheme: Literal["http", "https"] = "http"

    def __post_init__(self):
        if (
            not isinstance(self.host, str)
            or not self.host
            or any(character in self.host for character in "@/?#\\% \t\r\n")
            or type(self.port) is not int
            or not 0 < self.port <= 65535
            or self.scheme not in ("http", "https")
        ):
            raise ValueError("服务端点不合法")
        endpoint = urlsplit(self.origin)
        if endpoint.hostname is None or endpoint.port != self.port:
            raise ValueError("服务端点不合法")

    @property
    def origin(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        return f"{self.scheme}://{host}:{self.port}"


class InstanceProvider(Protocol):
    async def query(self, service: str) -> list[ServiceInstance]: ...


@dataclass(frozen=True)
class Snapshot:
    instances: tuple[ServiceInstance, ...]
    confirmed_at: float


class ServiceDiscovery:
    MAX_STALE_SECONDS = 60

    def __init__(self, provider: InstanceProvider, *, clock: Callable[[], float] = time.monotonic):
        self.provider = provider
        self.clock = clock
        self.snapshots: dict[str, Snapshot] = {}
        self._positions: dict[str, int] = {}
        self._tasks: dict[str, asyncio.Task] = {}

    async def refresh(self, service: str) -> None:
        instances = await self.provider.query(service)
        self.snapshots[service] = Snapshot(tuple(instances), self.clock())

    async def watch(self, service: str) -> None:
        if service in self._tasks:
            return
        await self.refresh(service)

        async def reconcile():
            while True:
                await asyncio.sleep(15)
                try:
                    await self.refresh(service)
                except (DependencyUnavailable, TimeoutError, OSError):
                    # 保留时间戳；到 60 秒拒绝继续使用陈旧实例。
                    continue

        self._tasks[service] = asyncio.create_task(reconcile(), name=f"discovery-{service}")

    def choose(self, service: str) -> ServiceInstance:
        snapshot = self.snapshots.get(service)
        if (
            not snapshot
            or not snapshot.instances
            or self.clock() - snapshot.confirmed_at > self.MAX_STALE_SECONDS
        ):
            raise DependencyUnavailable("没有已确认且未过期的可用服务实例")
        position = self._positions.get(service, 0)
        self._positions[service] = position + 1
        return snapshot.instances[position % len(snapshot.instances)]

    def invalidate(self, service: str, instance: ServiceInstance) -> None:
        snapshot = self.snapshots.get(service)
        if snapshot:
            self.snapshots[service] = Snapshot(
                tuple(item for item in snapshot.instances if item != instance),
                snapshot.confirmed_at,
            )

    async def close(self) -> None:
        tasks, self._tasks = list(self._tasks.values()), {}
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
