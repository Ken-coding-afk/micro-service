"""由原生 JVM 集成测试调用；绑定/秘密均只读，仅输出脱敏验证字段。"""

import asyncio
import json
import sys
from pathlib import Path
from urllib.parse import urlsplit

import httpx

from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.security import ResourceIdentitySettings, ResourceIntrospector


async def main():
    binding = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    assert binding["test_only"] is True
    origin = urlsplit(binding["origin"])
    assert origin.scheme == "http" and origin.hostname == "127.0.0.1"

    class Provider:
        async def query(self, service):
            assert service == "identity-service"
            # JVM 已真实启动的隔离 HTTP 地址；该探针不声称验证 Nacos 寻址。
            return [ServiceInstance(origin.hostname, origin.port, "http")]

    discovery = ServiceDiscovery(Provider())
    await discovery.refresh("identity-service")
    settings = ResourceIdentitySettings(
        "local",
        binding["issuer"],
        binding["audience"],
        binding["client_id"],
        Path(binding["secret_file"]),
    )
    async with httpx.AsyncClient(trust_env=False) as http:
        client = ResourceIntrospector(discovery, http, settings)
        try:
            principal = await client.introspect(
                Path(binding["token_file"]).read_text(encoding="utf-8")
            )
            result = {
                "active": True,
                "subject": principal.subject,
                "principal_type": principal.principal_type,
                "tenant_id": str(principal.tenant_id) if principal.tenant_id else None,
                "client_id": principal.client_id,
                "permissions": sorted(principal.permissions),
                "data_scopes": {key: value.value for key, value in principal.data_scopes.items()},
            }
        except PlatformError as error:
            result = {"active": False, "status": error.status, "code": error.code}
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
