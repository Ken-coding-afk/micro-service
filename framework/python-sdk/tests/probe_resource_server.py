"""独立真实 ASGI/HTTP 进程；身份与回调 API 都是真实 HTTP，发现地址是测试夹具。"""

import asyncio
import json
import socket
import sys
from pathlib import Path
from urllib.parse import urlsplit

import httpx
import uvicorn
from fastapi import Depends, FastAPI

from ken_platform_sdk.context import current_context
from ken_platform_sdk.discovery import ServiceDiscovery, ServiceInstance
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.http import ServiceHttpClient
from ken_platform_sdk.security import ResourceIdentitySettings, ResourceIntrospector
from ken_platform_sdk.security_web import (
    current_principal,
    install_resource_security,
    require_permission,
)


async def run(binding_file: str) -> None:
    binding = json.loads(Path(binding_file).read_text(encoding="utf-8"))
    if binding.get("test_only") is not True:
        raise ValueError("仅接受 test_only 绑定")
    target = urlsplit(binding["identity_origin"])
    if target.scheme != "http" or target.hostname != "127.0.0.1" or not target.port:
        raise ValueError("探针只能连接 loopback 真实身份服务")

    class Directory:
        async def query(self, service):
            if service != "identity-service":
                raise ValueError("未登记测试服务")
            return [ServiceInstance("127.0.0.1", target.port)]

    directory = ServiceDiscovery(Directory())
    await directory.refresh("identity-service")
    try:
        async with httpx.AsyncClient(follow_redirects=False) as http:
            introspector = ResourceIntrospector(
                directory,
                http,
                ResourceIdentitySettings(
                    "local",
                    binding["issuer"],
                    binding["audience"],
                    binding["client_id"],
                    Path(binding["secret_file"]),
                ),
            )
            await introspector.verify_identity()
            client = ServiceHttpClient(directory, http)
            app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

            @app.get("/actuator/health")
            async def health():
                return {"status": "UP"}

            @app.get(
                "/api/v1/analysis-jobs/probe",
                dependencies=[Depends(require_permission(binding["scope"], binding["permission"]))],
            )
            async def probe():
                principal, context = current_principal(), current_context.get()
                # 原用户通过 Python SDK 返回 Java API，不传任意 user/tenant header。
                response = await client.request("identity-service", "GET", "/api/v1/identity/me")
                if response.status_code != 200:
                    raise PlatformError("IDENTITY_UNAVAILABLE", "Java 本人接口当前不可用", 503)
                envelope = response.json()
                remote = envelope["data"]
                if (
                    remote["sub"] != principal.subject
                    or remote["tenant_id"] != str(principal.tenant_id)
                    or envelope["request_id"] != context.request_id
                ):
                    raise PlatformError("FORBIDDEN", "跨语言身份上下文不一致", 403)
                return {
                    "code": "OK",
                    "message": "success",
                    "request_id": context.request_id,
                    "data": {
                        "subject": principal.subject,
                        "tenant_id": str(principal.tenant_id),
                        "remote_subject": remote["sub"],
                        "client_id": remote["client_id"],
                        "data_scope": principal.data_scope(binding["permission"]).value,
                    },
                }

            install_resource_security(app, introspector)
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                listener.setblocking(False)
                port = listener.getsockname()[1]
                server = uvicorn.Server(
                    uvicorn.Config(
                        app,
                        host="127.0.0.1",
                        port=port,
                        loop="asyncio",
                        http="h11",
                        lifespan="on",
                        proxy_headers=False,
                        access_log=False,
                        log_level="error",
                        timeout_graceful_shutdown=5,
                    )
                )
                serving = asyncio.create_task(server.serve(sockets=[listener]))
                try:
                    while not server.started:
                        if serving.done():
                            await serving
                            raise RuntimeError("ASGI 服务未启动")
                        await asyncio.sleep(0.01)
                    Path(binding["ready_file"]).write_text(
                        json.dumps({"test_only": True, "origin": f"http://127.0.0.1:{port}"}),
                        encoding="utf-8",
                    )
                    await asyncio.to_thread(sys.stdin.buffer.read, 1)
                finally:
                    server.should_exit = True
                    await asyncio.wait_for(serving, 10)
    finally:
        await directory.close()


if __name__ == "__main__":
    asyncio.run(run(sys.argv[1]))
