"""缓存键与对象边界；对象记录由服务从数据库加载，范围由统一授权层计算。"""

from __future__ import annotations

import base64
import re
import uuid
from dataclasses import dataclass
from typing import Literal

from .context import current_context
from .errors import PlatformError


def _invalid() -> PlatformError:
    return PlatformError("RESOURCE_KEY_INVALID", "资源键或预签名期限不合法", 400)


def _segment(value: str) -> None:
    if not isinstance(value, str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", value):
        raise _invalid()


def _tenant() -> uuid.UUID:
    context = current_context.get()
    if context is None or not isinstance(context.tenant_id, uuid.UUID):
        raise PlatformError("TENANT_CONTEXT_REQUIRED", "需要已验证的租户上下文", 403)
    return context.tenant_id


@dataclass(frozen=True)
class ObjectRecord:
    tenant_id: uuid.UUID
    resource: str
    object_id: uuid.UUID
    owner_user_id: uuid.UUID | None


@dataclass(frozen=True)
class TenantResources:
    project: str
    environment: str
    service: str

    def __post_init__(self) -> None:
        _segment(self.project)
        _segment(self.service)
        if self.environment not in ("local", "test", "prod"):
            raise _invalid()

    def cache_key(self, purpose: str, business_key: str) -> str:
        tenant = _tenant()
        _segment(purpose)
        if (
            not isinstance(business_key, str)
            or not business_key
            or len(business_key) > 512
            or any(ord(value) < 32 or ord(value) == 127 for value in business_key)
        ):
            raise _invalid()
        try:
            raw = business_key.encode("utf-8")
        except UnicodeEncodeError:
            raise _invalid() from None
        if len(raw) > 512:
            raise _invalid()
        encoded = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
        return f"{self.project}:{self.environment}:{self.service}:{tenant}:{purpose}:{encoded}"

    def new_object_key(self, resource: str) -> str:
        tenant = _tenant()
        _segment(resource)
        return self._object_key(tenant, resource, uuid.uuid4())

    def authorized_object_key(
        self, record: ObjectRecord | None, effective_scope: Literal["SELF", "TENANT"] | None
    ) -> str:
        tenant = _tenant()
        if record is None or record.tenant_id != tenant:
            raise PlatformError("OBJECT_NOT_FOUND", "对象不存在", 404)
        context = current_context.get()
        if effective_scope not in ("SELF", "TENANT") or (
            effective_scope == "SELF"
            and (
                record.owner_user_id is None
                or context is None
                or str(record.owner_user_id) != context.principal_id
            )
        ):
            raise PlatformError("OBJECT_ACCESS_DENIED", "无权访问该对象", 403)
        _segment(record.resource)
        if not isinstance(record.object_id, uuid.UUID):
            raise _invalid()
        return self._object_key(tenant, record.resource, record.object_id)

    def _object_key(self, tenant: uuid.UUID, resource: str, object_id: uuid.UUID) -> str:
        return f"{self.project}/{self.environment}/{tenant}/{resource}/{object_id}"


def presign_seconds(requested: int = 300) -> int:
    """只约束签发期限；实际 S3 签发器必须在对象授权成功后调用。"""
    if isinstance(requested, bool) or not isinstance(requested, int) or not 1 <= requested <= 300:
        raise _invalid()
    return requested
