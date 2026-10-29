"""允许公开的业务投影；不包含租户、所有者或持久化对象。"""

from ken_platform_sdk.context import current_context
from pydantic import BaseModel, ConfigDict, Field


class CreateItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=120)


class Item(BaseModel):
    model_config = ConfigDict(frozen=True)
    id: str
    name: str


class ItemPage(BaseModel):
    items: list[Item]
    total: int
    page: int
    size: int
    has_more: bool


class ApiResponse[T](BaseModel):
    code: str = "OK"
    message: str = "success"
    data: T
    request_id: str


def response[T](data: T) -> ApiResponse[T]:
    context = current_context.get()
    if context is None:
        raise RuntimeError("响应必须在已安装的请求上下文中生成")
    return ApiResponse(data=data, request_id=context.request_id)
