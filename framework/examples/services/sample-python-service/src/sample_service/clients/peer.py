"""依赖适配只选择目标与解释响应；预算和重试复用 SDK。"""

from typing import cast

from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.http import ServiceHttpClient


class PeerClient:
    def __init__(self, http: ServiceHttpClient, service: str | None) -> None:
        self.http = http
        self.service = service

    async def list(self) -> object:
        if self.service != "sample-java-service":
            raise PlatformError("PEER_NOT_CONFIGURED", "未启用 Java 协作示例", 503)
        result = await self.http.request("sample-java-service", "GET", "/api/v1/java-items")
        if result.status_code != 200:
            raise PlatformError("PEER_REJECTED", "下游未返回成功结果", result.status_code)
        # 保留原 peer JSON 合同；这是已认证远端的结果，不用于建立身份。
        return cast(object, result.json())
