"""使用成熟指标库；由项目已有内网观测入口导出，不新增公共端点。"""

from prometheus_client import (
    CollectorRegistry,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
    start_http_server,
)


class ProtectionMetrics:
    def __init__(self):
        self.registry = CollectorRegistry()
        self.server = None
        self.thread = None
        self.duration = Histogram(
            "platform_http_request_duration_seconds",
            "HTTP 服务耗时",
            ["method", "route", "status"],
            registry=self.registry,
        )
        self.circuit = Gauge(
            "platform_dependency_circuit_state",
            "熔断状态 0=closed 1=open 2=half_open",
            ["dependency"],
            registry=self.registry,
        )
        self.enabled = True
        self.cancelled = Counter(
            "platform_protection_cancelled", "调用取消次数", ["direction"], registry=self.registry
        )
        self.rejected = Counter(
            "platform_protection_rejected",
            "保护拒绝次数",
            ["resource", "reason"],
            registry=self.registry,
        )
        self.active = Gauge(
            "platform_protection_active", "当前实际占用名额", ["resource"], registry=self.registry
        )

    def reject(self, resource: str, reason: str):
        if self.enabled:
            self.rejected.labels(resource, reason).inc()

    def render(self) -> bytes:
        return generate_latest(self.registry) if self.enabled else b""

    def start(self, host: str, port: int) -> None:
        if port and self.server is None:
            self.server, self.thread = start_http_server(port, addr=host, registry=self.registry)

    def stop(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=2)
            self.server = self.thread = None
