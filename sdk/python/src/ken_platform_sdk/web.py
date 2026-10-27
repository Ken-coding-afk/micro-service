"""纯 ASGI 上下文/错误适配；不把外部 user/tenant header 当成身份。"""

import logging
import time
import uuid

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from .concurrency import admission
from .context import current_context, request_context
from .errors import PlatformError


def _api(path: str) -> bool:
    return path in {"/api", "/internal", "/bff"} or path.startswith(
        ("/api/", "/internal/", "/bff/")
    )


def _response(error: PlatformError, request_id: str) -> JSONResponse:
    headers = {
        k: v
        for k, v in error.headers.items()
        if k.lower() in {"allow", "retry-after", "www-authenticate"}
    }
    headers.update({"Cache-Control": "no-store", "X-Request-Id": request_id})
    return JSONResponse(
        {"code": error.code, "message": str(error), "data": None, "request_id": request_id},
        status_code=error.status,
        headers=headers,
    )


def install_error_handlers(app) -> None:
    """业务 API 的框架错误；协议/静态页使用原有 handler。"""
    from fastapi.exception_handlers import (
        http_exception_handler,
        request_validation_exception_handler,
    )
    from fastapi.exceptions import RequestValidationError
    from starlette.exceptions import HTTPException

    if getattr(app.state, "platform_errors_installed", False):
        return
    app.state.platform_errors_installed = True

    def request_id(request):
        context = current_context.get() or getattr(request.state, "platform_context", None)
        return context.request_id if context else uuid.uuid4().hex

    async def validation(request, error):
        if not _api(request.url.path):
            return await request_validation_exception_handler(request, error)
        malformed = any(item["type"] == "json_invalid" for item in error.errors())
        return _response(
            PlatformError(
                "INVALID_REQUEST" if malformed else "VALIDATION_ERROR",
                "请求格式不合法" if malformed else "请求参数不合法",
                400,
            ),
            request_id(request),
        )

    async def http_error(request, error):
        if not _api(request.url.path):
            return await http_exception_handler(request, error)
        names = {
            400: ("INVALID_REQUEST", "请求格式不合法"),
            401: ("INVALID_CREDENTIALS", "访问令牌无效"),
            403: ("FORBIDDEN", "无权访问"),
            404: ("NOT_FOUND", "资源不存在"),
            405: ("METHOD_NOT_ALLOWED", "请求方法不支持"),
            415: ("UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不支持"),
            429: ("RATE_LIMITED", "请求过于频繁"),
            504: ("UPSTREAM_TIMEOUT", "调用预算已耗尽；写入结果可能需要核对"),
        }
        code, message = names.get(
            error.status_code,
            ("INTERNAL_ERROR", "服务暂时无法处理请求")
            if error.status_code >= 500
            else ("INVALID_REQUEST", "请求格式不合法"),
        )
        return _response(
            PlatformError(code, message, error.status_code, headers=error.headers),
            request_id(request),
        )

    app.add_exception_handler(RequestValidationError, validation)
    app.add_exception_handler(HTTPException, http_error)


class PlatformMiddleware:
    def __init__(self, app: ASGIApp, runtime=None):
        self.app = app
        self.runtime = runtime

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
            started = time.monotonic()
            status = 500

            async def send_with_request_id(message: Message) -> None:
                nonlocal response_started, status
                if message["type"] == "http.response.start":
                    response_started = True
                    status = message["status"]
                    message["headers"] = [
                        (k, v)
                        for k, v in message.get("headers", [])
                        if k.lower() != b"x-request-id"
                    ] + [(b"x-request-id", context.request_id.encode("ascii"))]
                await send(message)

            try:
                path = scope.get("path", "")
                protected = path.startswith(("/api/v1/", "/internal/v1/"))
                limiter = self.runtime.inbound if self.runtime and protected else None
                with admission(
                    limiter, inbound=True, metrics=self.runtime.metrics if self.runtime else None
                ):
                    await self.app(scope, receive, send_with_request_id)
            except PlatformError as exc:
                if response_started:
                    raise  # 已建立的流由流协议处理/关闭，不能再伪造一个 HTTP 状态。
                response = _response(exc, context.request_id)
                await response(scope, receive, send_with_request_id)
            except Exception as exc:
                if response_started or not _api(scope.get("path", "")):
                    raise
                logging.getLogger(__name__).error("请求处理失败 type=%s", type(exc).__name__)
                await _response(
                    PlatformError("INTERNAL_ERROR", "服务暂时无法处理请求", 500), context.request_id
                )(scope, receive, send_with_request_id)
            finally:
                if self.runtime and self.runtime.metrics.enabled:
                    route = getattr(scope.get("route"), "path", "unmatched")
                    method = scope.get("method", "OTHER")
                    if method not in {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}:
                        method = "OTHER"
                    self.runtime.metrics.duration.labels(method, route, str(status)).observe(
                        time.monotonic() - started
                    )
