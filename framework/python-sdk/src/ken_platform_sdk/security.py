"""真实 opaque 内省及最终权限检查；不解码 JWT，不缓存成功结果。"""

from __future__ import annotations

import asyncio
import ipaddress
import re
import time
import uuid
from collections.abc import Mapping
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from types import MappingProxyType
from urllib.parse import quote_plus, urlsplit

import httpx

from .config import HttpParameters
from .context import current_context, request_deadline
from .discovery import ServiceDiscovery
from .errors import PlatformError


class DataScope(StrEnum):
    SELF = "SELF"
    TENANT = "TENANT"


def _invalid() -> PlatformError:
    return PlatformError("INVALID_CREDENTIALS", "访问令牌无效", 401)


def _unavailable() -> PlatformError:
    return PlatformError("IDENTITY_UNAVAILABLE", "身份内省暂不可用", 503)


def _forbidden() -> PlatformError:
    return PlatformError("FORBIDDEN", "当前主体、授权范围或操作权限不足", 403)


@dataclass(frozen=True)
class ResourceIdentitySettings:
    app_env: str
    issuer: str
    audience: str
    client_id: str
    secret_file: Path = field(repr=False)

    def __post_init__(self):
        try:
            origin = urlsplit(self.issuer)
            if origin.hostname and ":" in origin.hostname:
                ipaddress.ip_address(origin.hostname)
            elif not origin.hostname or any(
                re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?", label) is None
                for label in origin.hostname.split(".")
            ):
                raise ValueError
            local = (
                self.app_env == "local"
                and origin.scheme == "http"
                and origin.hostname in {"localhost", "127.0.0.1", "::1"}
            )
            if (
                self.app_env not in {"local", "test", "prod"}
                or not (origin.scheme == "https" or local)
                or not origin.hostname
                or origin.username is not None
                or origin.password is not None
                or origin.query
                or origin.fragment
                or "?" in self.issuer
                or "#" in self.issuer
                or origin.path not in {"", "/"}
                or origin.port == 0
                or re.fullmatch(r"[A-Za-z0-9._:-]{1,160}", self.audience) is None
                or re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", self.client_id) is None
                or not self.secret_file.is_absolute()
                or not self.secret_file.is_file()
                or not self.secret_file.read_text(encoding="utf-8").strip()
            ):
                raise ValueError
        except (ValueError, TypeError, AttributeError, OSError, UnicodeError):
            raise ValueError("资源内省环境绑定或秘密引用不合法") from None

    @classmethod
    def from_environment(cls, values: Mapping[str, str]) -> ResourceIdentitySettings:
        try:
            if "INTROSPECTION_CLIENT_SECRET" in values:
                raise ValueError
            return cls(
                values["APP_ENV"],
                values["PUBLIC_ORIGIN"],
                values["RESOURCE_AUDIENCE"],
                values["INTROSPECTION_CLIENT_ID"],
                Path(values["INTROSPECTION_CLIENT_SECRET_FILE"]),
            )
        except (KeyError, ValueError, TypeError):
            raise ValueError("资源内省环境绑定或秘密引用不合法") from None

    def __repr__(self):
        return "ResourceIdentitySettings[protected]"


@dataclass(frozen=True)
class CurrentPrincipal:
    subject: str
    principal_type: str
    tenant_id: uuid.UUID | None
    client_id: str
    scopes: frozenset[str]
    permissions: frozenset[str]
    data_scopes: Mapping[str, DataScope]
    allowed_tenant_ids: frozenset[uuid.UUID]
    expires_at: int

    def require(self, scope: str, permission: str, principal_type: str) -> None:
        if (
            self.principal_type != principal_type
            or scope not in self.scopes
            or permission not in self.permissions
        ):
            raise _forbidden()

    def data_scope(self, permission: str) -> DataScope:
        if permission not in self.permissions or permission not in self.data_scopes:
            raise _forbidden()
        return self.data_scopes[permission]

    def require_tenant(self) -> uuid.UUID:
        if self.tenant_id is None or self.principal_type == "platform":
            raise _forbidden()
        return self.tenant_id


def _text(value, empty=False) -> str:
    if not isinstance(value, str) or (not empty and not value):
        raise _unavailable()
    return value


def _identifier(value) -> str:
    if re.fullmatch(r"[A-Za-z0-9._:-]{1,160}", _text(value)) is None:
        raise _unavailable()
    return value


def _uuid(value) -> uuid.UUID:
    result = uuid.UUID(_text(value))
    if str(result) != value:
        raise _unavailable()
    return result


def _strings(value, permissions=False) -> frozenset[str]:
    if not isinstance(value, list):
        raise _unavailable()
    pattern = r"[a-z][a-z0-9:_-]{0,159}" if permissions else r"[A-Za-z0-9._:-]{1,160}"
    if any(re.fullmatch(pattern, _text(item)) is None for item in value):
        raise _unavailable()
    return frozenset(value)


def _date(value) -> int:
    if type(value) is not int or not 0 <= value <= 253402300799:
        raise _unavailable()
    return value


def parse_principal(
    raw, issuer: str, audience: str, *, now: float | None = None
) -> CurrentPrincipal:
    try:
        if type(raw.get("active")) is not bool:
            raise _unavailable()
        if raw["active"] is False:
            raise _invalid()
        actual_issuer = _text(raw.get("iss"))
        audiences = (
            {_text(raw["aud"])} if isinstance(raw.get("aud"), str) else _strings(raw.get("aud"))
        )
        if actual_issuer != issuer or audience not in audiences:
            raise _invalid()
        expires = _date(raw.get("exp"))
        if expires <= (time.time() if now is None else now):
            raise _invalid()
        if "iat" in raw:
            _date(raw["iat"])
        kind = _text(raw.get("principal_type"))
        if kind not in {"user", "platform", "service"}:
            raise _unavailable()
        client = _identifier(raw.get("client_id"))
        subject = _text(raw.get("sub"))
        if kind == "service":
            if subject != client:
                raise _unavailable()
        else:
            _uuid(subject)
        tenant = _uuid(raw["tenant_id"]) if raw.get("tenant_id") is not None else None
        allowed = (
            frozenset(_uuid(value) for value in _strings(raw.get("allowed_tenant_ids")))
            if kind == "service"
            else frozenset()
        )
        if kind == "service":
            if (len(allowed) == 1 and tenant not in allowed) or (
                len(allowed) != 1 and tenant is not None
            ):
                raise _unavailable()
        elif (kind == "user" and tenant is None) or (kind == "platform" and tenant is not None):
            raise _unavailable()
        if (
            kind != "service"
            and "allowed_tenant_ids" in raw
            and _strings(raw["allowed_tenant_ids"])
        ):
            raise _unavailable()
        scopes = frozenset(
            _identifier(value) for value in _text(raw.get("scope"), empty=True).split(" ") if value
        )
        permissions = _strings(raw.get("permissions"), permissions=True)
        if not isinstance(raw.get("data_scopes"), dict):
            raise _unavailable()
        ranges = {}
        for key, value in raw["data_scopes"].items():
            if key not in permissions or not isinstance(value, str):
                raise _unavailable()
            ranges[key] = DataScope(value)
        if kind == "platform" and ranges:
            raise _unavailable()
        return CurrentPrincipal(
            subject,
            kind,
            tenant,
            client,
            scopes,
            permissions,
            MappingProxyType(ranges),
            allowed,
            expires,
        )
    except PlatformError:
        raise
    except (AttributeError, TypeError, ValueError, KeyError):
        raise _unavailable() from None


class ResourceIntrospector:
    def __init__(
        self,
        discovery: ServiceDiscovery,
        client: httpx.AsyncClient,
        settings: ResourceIdentitySettings,
        parameters: HttpParameters | None = None,
    ):
        self.discovery = discovery
        self.client = client
        self.settings = settings
        self.parameters = parameters or HttpParameters()
        try:
            secret = settings.secret_file.read_text(encoding="utf-8").strip()
            if not secret:
                raise ValueError
            self._auth = httpx.BasicAuth(
                quote_plus(settings.client_id, safe=""), quote_plus(secret, safe="")
            )
        except (OSError, UnicodeError, ValueError):
            raise ValueError("资源内省秘密文件不可用") from None

    async def introspect(self, token: str) -> CurrentPrincipal:
        if (
            not isinstance(token, str)
            or len(token) > 8192
            or re.fullmatch(r"[A-Za-z0-9._~+/-]+=*", token) is None
        ):
            raise _invalid()
        deadline = request_deadline(self.parameters.response_budget_seconds)
        remaining = deadline - time.time()
        if remaining <= 0:
            raise _unavailable()
        try:
            instance = self.discovery.choose("identity-service")
        except PlatformError:
            raise _unavailable() from None
        if self.settings.app_env != "local" and instance.scheme != "https":
            raise _unavailable()
        context = current_context.get()
        headers = {"X-Request-Deadline": str(int(deadline * 1000))}
        if context:
            headers["X-Request-Id"] = context.request_id
            if context.traceparent:
                headers["traceparent"] = context.traceparent
        try:
            async with asyncio.timeout(remaining):
                response = await self.client.post(
                    instance.origin + "/oauth2/introspect",
                    data={"token": token},
                    headers=headers,
                    auth=self._auth,
                    follow_redirects=False,
                    timeout=httpx.Timeout(
                        remaining, connect=min(remaining, self.parameters.connect_timeout_seconds)
                    ),
                )
                if response.status_code != 200 or len(response.content) > 1_048_576:
                    raise _unavailable()
                return parse_principal(
                    response.json(), self.settings.issuer, self.settings.audience
                )
        except PlatformError:
            raise
        except (TimeoutError, httpx.TransportError):
            self.discovery.invalidate("identity-service", instance)
            raise _unavailable() from None
        except (ValueError, TypeError, httpx.RequestError, httpx.InvalidURL):
            raise _unavailable() from None

    async def verify_identity(self) -> None:
        """在资源注册前核对协议和客户端身份；不能把未知 token 的 active 当作成功。"""
        try:
            await self.introspect("startup-" + uuid.uuid4().hex)
        except PlatformError as error:
            if error.status == 401:
                return
            raise
        raise _unavailable()
