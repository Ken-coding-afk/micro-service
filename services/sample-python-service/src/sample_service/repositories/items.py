"""本服务的参数化 SQL；事务由用例层管理。"""

import uuid

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

from sample_service.schemas.items import Item, ItemPage


class ItemRepository:
    async def create(
        self, session: AsyncSession, item: Item, tenant: uuid.UUID, owner: uuid.UUID
    ) -> None:
        await session.execute(
            text(
                "INSERT INTO sample_item(id,tenant_id,owner_user_id,name) VALUES(:id,:tenant,:owner,:name)"
            ),
            {"id": uuid.UUID(item.id), "tenant": tenant, "owner": owner, "name": item.name},
        )

    async def list(
        self,
        session: AsyncSession,
        tenant: uuid.UUID,
        owner: uuid.UUID,
        own: bool,
        page: int,
        size: int,
    ) -> ItemPage:
        params = {
            "tenant": tenant,
            "own": own,
            "owner": owner,
            "limit": size,
            "offset": (page - 1) * size,
        }
        predicate = "tenant_id=:tenant AND (NOT :own OR owner_user_id=:owner)"
        count = (
            await session.execute(
                text("SELECT count(*) FROM sample_item WHERE " + predicate), params
            )
        ).scalar_one()
        rows = (
            (
                await session.execute(
                    text(
                        "SELECT id,name FROM sample_item WHERE "
                        + predicate
                        + " ORDER BY id LIMIT :limit OFFSET :offset"
                    ),
                    params,
                )
            )
            .mappings()
            .all()
        )
        return ItemPage(
            items=[Item(id=str(row["id"]), name=row["name"]) for row in rows],
            total=count,
            page=page,
            page_size=size,
        )


async def verify_database(engine: AsyncEngine) -> None:
    """启动检查也属于持久化适配；装配层只调用，不内嵌 SQL。"""
    async with engine.connect() as connection:
        await connection.execute(text("SELECT id FROM sample_item LIMIT 0"))
