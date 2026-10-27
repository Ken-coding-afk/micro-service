"""纯 ASGI 上下文/错误适配；不把外部 user/tenant header 当成身份。"""

import time

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from .context import request_context
from .errors import PlatformError


class PlatformMiddleware:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = {
            key.decode("latin-1").lower(): value.decode("latin-1")
            for key, value in scope.get("headers", [])
        }
        deadline = time.time() + 10
        try:
            if "x-request-deadline" in headers:
                # 先在整数域限制到本地预算，避免极大数字转 float 溢出。
                deadline = (
                    max(0, min(int(headers["x-request-deadline"]), int(deadline * 1000))) / 1000
                )
        except ValueError:
            # 非法外部预算不能扩大本地上限。
            pass
        with request_context(
            request_id=headers.get("x-request-id"),
            traceparent=headers.get("traceparent"),
            deadline=deadline,
        ) as context:
            response_started = False

            async def send_with_request_id(message: Message) -> None:
                nonlocal response_started
                if message["type"] == "http.response.start":
                    response_started = True
                    message["headers"] = list(message.get("headers", [])) + [
                        (b"x-request-id", context.request_id.encode("ascii"))
                    ]
                await send(message)

            try:
                await self.app(scope, receive, send_with_request_id)
            except PlatformError as exc:
                if response_started:
                    raise  # 已建立的流由流协议处理/关闭，不能再伪造一个 HTTP 状态。
                response = JSONResponse(
                    {
                        "code": exc.code,
                        "message": str(exc),
                        "data": None,
                        "request_id": context.request_id,
                    },
                    status_code=exc.status,
                    headers=exc.headers,
                )
                await response(scope, receive, send_with_request_id)
