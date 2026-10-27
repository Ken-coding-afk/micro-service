"""可选 db extra：每次任务独立 session/事务，RLS 值仅在本次事务生效。"""

import asyncio
import selectors
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from .context import current_context
from .errors import PlatformError


def database_loop_factory() -> asyncio.AbstractEventLoop:
    """Windows DB 进程显式传给 asyncio.Runner；不在导入 SDK 时改全局策略。"""
    if sys.platform == "win32":
        return asyncio.SelectorEventLoop(selectors.SelectSelector())
    return asyncio.new_event_loop()


@asynccontextmanager
async def tenant_transaction(
    factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    context = current_context.get()
    if context is None or context.tenant_id is None:
        raise PlatformError("TENANT_CONTEXT_REQUIRED", "缺少可信租户上下文", 403)
    async with factory() as session, session.begin():
        await session.execute(
            text("SELECT set_config('app.tenant_id', :tenant, true)"),
            {"tenant": str(context.tenant_id)},
        )
        yield session
