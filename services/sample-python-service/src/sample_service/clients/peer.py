"""依赖适配只选择目标与解释响应；预算和重试复用 SDK。"""

from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.http import ServiceHttpClient
from pydantic import BaseModel


class PeerItem(BaseModel):
    id: str
    name: str


class PeerPage(BaseModel):
    items: list[PeerItem]
    total: int
    page: int
    size: int
    has_more: bool


class PeerResponse(BaseModel):
    code: str
    message: str
    data: PeerPage
    request_id: str


class PeerClient:
    def __init__(self, http: ServiceHttpClient, service: str | None) -> None:
        self.http = http
        self.service = service

    async def list(self) -> PeerResponse:
        if self.service != "sample-java-service":
            raise PlatformError("PEER_NOT_CONFIGURED", "未启用 Java 协作示例", 503)
        return await self.http.request_model(
            "sample-java-service", "GET", "/api/v1/java-items", PeerResponse
        )
