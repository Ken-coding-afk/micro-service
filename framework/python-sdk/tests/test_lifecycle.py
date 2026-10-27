import pytest

from ken_platform_sdk.lifecycle import PlatformRuntime
from test_config import SHARED, settings


class Provider:
    def __init__(self):
        self.calls = []
        self.invalid = False

    async def open(self):
        self.calls.append("open")

    async def close(self):
        self.calls.append("close")

    async def register(self):
        self.calls.append("register")

    async def deregister(self):
        self.calls.append("deregister")

    async def watch_config(self, data_ids, refresh):
        self.calls.append("watch_config")
        self.refresh = refresh

    async def get_config(self, name):
        self.calls.append(name)
        return (
            "BAD: secret"
            if self.invalid
            else (SHARED if name == "application-shared.yaml" else "log_level: INFO")
        )


async def test_readiness_checks_precede_registration_and_stop_deregisters_first(tmp_path):
    provider = Provider()
    runtime = PlatformRuntime(settings(tmp_path), provider)

    async def check():
        provider.calls.append("check")

    await runtime.start([check])
    assert runtime.ready
    assert provider.calls.index("check") < provider.calls.index("register")
    assert provider.calls.index("watch_config") < provider.calls.index("register")
    await runtime.stop()
    assert not runtime.ready and runtime.http is None
    assert provider.calls[-2:] == ["deregister", "close"]


async def test_worker_never_registers_http_endpoint(tmp_path):
    provider = Provider()
    runtime = PlatformRuntime(settings(tmp_path, role="worker"), provider)
    await runtime.start()
    await runtime.stop()
    assert "register" not in provider.calls and "deregister" not in provider.calls


async def test_invalid_first_snapshot_never_ready_and_hot_failure_keeps_old(tmp_path):
    provider = Provider()
    runtime = PlatformRuntime(settings(tmp_path), provider)
    await runtime.start()
    before = runtime.parameters
    assert not runtime.apply_hot_update(SHARED, "business: {page_limit: 50}")
    assert runtime.parameters is before
    assert not runtime.apply_hot_update(SHARED, "client_secret: private")
    assert runtime.parameters is before
    assert not runtime.apply_hot_update(SHARED.replace("10", "20"), "log_level: INFO")
    assert runtime.parameters is before
    provider.invalid = True
    await provider.refresh()
    assert runtime.parameters is before
    provider.invalid = False
    await runtime.stop()
    provider.invalid = True
    with pytest.raises(ValueError):
        await runtime.start()
    assert not runtime.ready
