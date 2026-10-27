import asyncio
import sys


def pytest_asyncio_loop_factories(config, item):
    if sys.platform == "win32" and item.path.name in {
        "test_tenant_isolation.py",
        "test_messaging.py",
    }:
        from ken_platform_sdk.db import database_loop_factory

        return {"windows-db-selector": database_loop_factory}
    # Java 启停用例需要 Windows Proactor 子进程支持；不能全仓改为 Selector。
    return {"default": asyncio.new_event_loop}
