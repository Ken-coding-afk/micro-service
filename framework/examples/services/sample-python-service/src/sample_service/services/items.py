"""用例控制可信主体、数据范围和事务；不依赖 HTTP 应用。"""

import uuid
from collections.abc import Callable
from contextlib import AbstractAsyncContextManager

from ken_platform_sdk.security import CurrentPrincipal, DataScope
from sqlalchemy.ext.asyncio import AsyncSession

from sample_service.config import BusinessSettings
from sample_service.repositories.items import ItemRepository
from sample_service.schemas.items import Item, ItemPage


class ItemService:
    def __init__(
        self,
        repository: ItemRepository,
        transaction: Callable[[], AbstractAsyncContextManager[AsyncSession]],
        settings: BusinessSettings,
    ) -> None:
        self.repository = repository
        self.transaction = transaction
        self.settings = settings

    async def create(self, principal: CurrentPrincipal, name: str) -> Item:
        tenant = principal.require_tenant()
        owner = uuid.UUID(principal.subject)
        item = Item(id=str(uuid.uuid4()), name=name)
        async with self.transaction() as session:
            await self.repository.create(session, item, tenant, owner)
        return item

    async def list(self, principal: CurrentPrincipal, page: int, size: int) -> ItemPage:
        tenant = principal.require_tenant()
        owner = uuid.UUID(principal.subject)
        own = principal.data_scope("python-item:read") == DataScope.SELF
        async with self.transaction() as session:
            return await self.repository.list(
                session, tenant, owner, own, page, min(size, self.settings.max_page_size)
            )
