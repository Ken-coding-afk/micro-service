import pytest

from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
from ken_platform_sdk.errors import DependencyUnavailable


@pytest.mark.parametrize(
    "host", ["other.example/private", "user@other.example", "other.example?x=1"]
)
def test_instance_host_cannot_inject_credentials_or_path(host):
    with pytest.raises(ValueError):
        ServiceInstance(host, 8100)


class Provider:
    fail = False

    async def query(self, service):
        if self.fail:
            raise DependencyUnavailable()
        return [ServiceInstance("192.0.2.1", 8001), ServiceInstance("192.0.2.2", 8001)]


async def test_failure_does_not_extend_sixty_second_stale_boundary():
    now = [0.0]
    provider = Provider()
    directory = ServiceDiscovery(provider, clock=lambda: now[0])
    await directory.refresh("analysis")
    provider.fail = True
    now[0] = 60.0
    with pytest.raises(DependencyUnavailable):
        await directory.refresh("analysis")
    assert directory.choose("analysis").host == "192.0.2.1"
    now[0] = 60.001
    with pytest.raises(DependencyUnavailable):
        directory.choose("analysis")


async def test_failed_instance_is_removed_without_refreshing_age():
    directory = ServiceDiscovery(Provider())
    await directory.refresh("analysis")
    first = directory.choose("analysis")
    before = directory.snapshots["analysis"].confirmed_at
    directory.invalidate("analysis", first)
    assert directory.choose("analysis") != first
    assert directory.snapshots["analysis"].confirmed_at == before


async def test_watch_is_idempotent_and_close_cancels_background_tasks():
    directory = ServiceDiscovery(Provider())
    await directory.watch("analysis")
    await directory.watch("analysis")
    tasks = list(directory._tasks.values())
    assert len(tasks) == 1
    await directory.close()
    assert all(task.done() for task in tasks)
