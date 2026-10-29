"""协议校验、权限入口与结果转换；无 SQL 或客户端装配。"""

from typing import Annotated, cast

from fastapi import APIRouter, Depends, Query, Request
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.security import CurrentPrincipal
from ken_platform_sdk.security_web import require_permission

from sample_service.clients.peer import PeerClient
from sample_service.schemas.items import ApiResponse, CreateItem, Item, ItemPage, response
from sample_service.services.items import ItemService

router = APIRouter(prefix="/api/v1/python-items")
read_permission = require_permission("python.items.read", "python-item:read")
write_permission = require_permission("python.items.write", "python-item:create")


def item_service(request: Request) -> ItemService:
    return cast(ItemService, request.app.state.items)


def peer_client(request: Request) -> PeerClient:
    return cast(PeerClient, request.app.state.peer)


@router.post("", status_code=201)
async def create(
    value: CreateItem,
    principal: Annotated[CurrentPrincipal, Depends(write_permission)],
    service: Annotated[ItemService, Depends(item_service)],
) -> ApiResponse[Item]:
    return response(await service.create(principal, value.name))


@router.get("")
async def listing(
    request: Request,
    principal: Annotated[CurrentPrincipal, Depends(read_permission)],
    service: Annotated[ItemService, Depends(item_service)],
    page: int = Query(0, ge=0, le=10000),
    size: int = Query(25, ge=1, le=100),
) -> ApiResponse[ItemPage]:
    if "page_size" in request.query_params:
        raise PlatformError("VALIDATION_ERROR", "分页参数请使用 page 和 size", 400)
    return response(await service.list(principal, page, size))


@router.get("/peer")
async def peer(
    principal: Annotated[CurrentPrincipal, Depends(read_permission)],
    client: Annotated[PeerClient, Depends(peer_client)],
) -> object:
    return await client.list()
