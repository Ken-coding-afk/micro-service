"""使用成熟指标库；由项目已有内网观测入口导出，不新增公共端点。"""

from prometheus_client import CollectorRegistry, Counter, Gauge, generate_latest


class ProtectionMetrics:
    def __init__(self):
        self.registry = CollectorRegistry()
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
