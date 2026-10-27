"""FastAPI 路由权限依赖；认证上下文仅在请求作用域存活。"""

import os
import re
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from contextvars import ContextVar

from fastapi import FastAPI, Request
from fastapi.routing import APIRoute, iter_route_contexts

from .context import current_context, request_context
from .errors import PlatformError
from .security import CurrentPrincipal, ResourceIntrospector
from .web import PlatformMiddleware

_CURRENT: ContextVar[CurrentPrincipal | None] = ContextVar("platform_principal", default=None)
_HEALTH = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}


def _invalid():
    return PlatformError(
        "INVALID_CREDENTIALS",
        "访问令牌无效",
        401,
        headers={"WWW-Authenticate": 'Bearer error="invalid_token"'},
    )


def _forbidden():
    return PlatformError(
        "FORBIDDEN",
        "当前身份或授权范围不足",
        403,
        headers={"WWW-Authenticate": 'Bearer error="insufficient_scope"'},
    )


def _bearer(request: Request, header: str) -> str:
    values = request.headers.getlist(header)
    if (
        len(values) != 1
        or re.fullmatch(r"Bearer ([A-Za-z0-9._~+/-]+=*)", values[0], re.IGNORECASE) is None
    ):
        raise _invalid()
    token = values[0][7:]
    if len(token) > 8192:
        raise _invalid()
    return token


def current_principal() -> CurrentPrincipal:
    principal = _CURRENT.get()
    if principal is None:
        raise _invalid()
    return principal


async def resource_identity_readiness(runtime, introspector: ResourceIntrospector) -> None:
    """作为 PlatformRuntime.start 的依赖检查运行，先发现并验证身份，再注册本 API。"""
    await runtime.watch("identity-service")
    await introspector.verify_identity()


def require_permission(
    scope: str,
    permission: str,
    *,
    principal_type: str = "user",
    service_scope: str | None = None,
    service_permission: str | None = None,
):
    if (
        principal_type not in {"user", "platform", "service"}
        or not re.fullmatch(r"[A-Za-z0-9._:-]{1,160}", scope)
        or not re.fullmatch(r"[a-z][a-z0-9:_-]{0,159}", permission)
    ):
        raise ValueError("路由必须声明合法主体、scope 和 permission")
    if (service_scope is None) != (service_permission is None):
        raise ValueError("内部服务授权必须同时声明 scope 和 permission")
    if service_scope is not None and (
        re.fullmatch(r"[A-Za-z0-9._:-]{1,160}", service_scope) is None
        or re.fullmatch(r"[a-z][a-z0-9:_-]{0,159}", service_permission or "") is None
    ):
        raise ValueError("内部服务授权声明不合法")

    async def dependency(request: Request) -> AsyncIterator[CurrentPrincipal]:
        client = getattr(request.app.state, "platform_introspector", None)
        if not isinstance(client, ResourceIntrospector):
            raise PlatformError("IDENTITY_UNAVAILABLE", "资源内省未完成装配", 503)
        token = _bearer(request, "Authorization")
        try:
            principal = await client.introspect(token)
            principal.require(scope, permission, principal_type)
            internal = request.url.path == "/internal" or request.url.path.startswith("/internal/")
            if service_scope is not None and not internal:
                raise _forbidden()
            if internal and principal.principal_type != "service":
                if service_scope is None:
                    raise _forbidden()
                machine = await client.introspect(_bearer(request, "X-Service-Authorization"))
                machine.require(service_scope, service_permission or "", "service")
                if (
                    principal.tenant_id is not None
                    and principal.tenant_id not in machine.allowed_tenant_ids
                ):
                    raise _forbidden()
                request.state.platform_service_principal = machine
        except PlatformError as failure:
            if failure.status == 401:
                raise _invalid() from None
            if failure.status == 403:
                raise _forbidden() from None
            raise
        parent = current_context.get()
        if parent and (
            (parent.tenant_id is not None and parent.tenant_id != principal.tenant_id)
            or (parent.principal_id is not None and parent.principal_id != principal.subject)
        ):
            raise _forbidden()
        with request_context(
            request_id=parent.request_id if parent else request.headers.get("X-Request-Id"),
            tenant_id=principal.tenant_id,
            principal_id=principal.subject,
            access_token=token,
            traceparent=parent.traceparent if parent else request.headers.get("traceparent"),
            deadline=parent.deadline if parent else None,
        ) as context:
            request.state.platform_context = context
            marker = _CURRENT.set(principal)
            try:
                yield principal
            finally:
                _CURRENT.reset(marker)

    dependency.__dict__["_platform_permission"] = True
    return dependency


def install_resource_security(app: FastAPI, introspector: ResourceIntrospector) -> None:
    """启动前检查全部业务路由；先安装配置好的 lifespan，再调用本函数。"""
    if getattr(app.state, "platform_introspector", None) is not None:
        raise ValueError("资源身份不能重复装配")
    app.state.platform_introspector = introspector
    if not any(middleware.cls is PlatformMiddleware for middleware in app.user_middleware):
        app.add_middleware(PlatformMiddleware)
    previous = app.router.lifespan_context

    def declared(dependant) -> bool:
        return bool(
            getattr(getattr(dependant, "call", None), "_platform_permission", False)
        ) or any(declared(child) for child in getattr(dependant, "dependencies", []))

    @asynccontextmanager
    async def secured_lifespan(application):
        local_docs = os.environ.get("APP_ENV") == "local" and (
            app.docs_url,
            app.redoc_url,
            app.openapi_url,
            app.swagger_ui_oauth2_redirect_url,
        ) == ("/docs", None, "/openapi.json", "/docs/oauth2-redirect")
        if not local_docs and any(
            value is not None for value in (app.docs_url, app.redoc_url, app.openapi_url)
        ):
            raise ValueError("资源应用必须关闭未鉴权 Docs/OpenAPI 路由；发行时单独导出 OpenAPI")
        # FastAPI 0.162 保留 include_router 分支；公开迭代器给出含前缀/依赖的实际路由。
        for route in iter_route_contexts(app.routes):
            if (
                local_docs
                and route.path in {"/docs", "/openapi.json", "/docs/oauth2-redirect"}
                and route.methods
                and route.methods <= {"GET", "HEAD"}
            ):
                continue
            if not isinstance(route.original_route, APIRoute):
                raise TypeError("资源应用不能挂载未验证的旁路入口")
            if route.path in _HEALTH and route.methods and route.methods <= {"GET", "HEAD"}:
                continue
            if not (
                route.path in {"/api", "/internal"}
                or (route.path is not None and route.path.startswith(("/api/", "/internal/")))
            ) or not declared(route.dependant):
                raise ValueError("业务资源路由必须位于固定 API 前缀并声明权限")
        async with previous(application) as state:
            yield state

    app.router.lifespan_context = secured_lifespan
