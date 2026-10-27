"""可选 db extra：每次任务独立 session/事务，RLS 值仅在本次事务生效。"""

import asyncio
import selectors
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from .context import current_context
from .errors import PlatformError


class DatabaseReadiness:
    """真实运行身份核对；启动和探测共用，不做迁移或授权变更。"""

    def __init__(self, engine: AsyncEngine, expected_user: str, expected_database: str):
        if not expected_user or not expected_database:
            raise ValueError("必须声明预期数据库和运行账号")
        self.engine, self.user, self.database = engine, expected_user, expected_database

    async def verify(self) -> None:
        try:
            async with asyncio.timeout(2), self.engine.connect() as connection:
                unsafe = (
                    await connection.execute(
                        text("""
SELECT current_user<>session_user OR current_user::text<>:user OR current_database()::text<>:database
 OR EXISTS(SELECT 1 FROM pg_roles WHERE rolname=session_user
   AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication))
 OR has_database_privilege(current_user,current_database(),'CREATE')
 OR EXISTS(SELECT 1 FROM pg_database WHERE datname=current_database() AND pg_has_role(current_user,datdba,'MEMBER'))
 OR EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
   AND (has_schema_privilege(current_user,n.oid,'CREATE') OR pg_has_role(current_user,n.nspowner,'MEMBER')))
 OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
     AND pg_has_role(current_user,c.relowner,'MEMBER'))
"""),
                        {"user": self.user, "database": self.database},
                    )
                ).scalar_one()
                if unsafe is not False:
                    raise ValueError("unsafe")
        except Exception:  # noqa: BLE001 - 依赖/探测回调任何失败都不能报告就绪
            raise ValueError("数据库连接或运行身份不满足最小权限合同") from None

    async def check(self) -> bool:
        try:
            await self.verify()
            return True
        except Exception:  # noqa: BLE001 - 依赖/探测回调任何失败都不能报告就绪
            return False


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
