"""请求作用域上下文；身份由认证适配器提供，不从客户端身份 header 推导。"""

from __future__ import annotations

import re
import time
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field

REQUEST_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


@dataclass(frozen=True)
class RequestContext:
    request_id: str
    tenant_id: uuid.UUID | None = None
    principal_id: str | None = None
    access_token: str | None = field(default=None, repr=False)
    traceparent: str | None = None
    deadline: float | None = None


current_context: ContextVar[RequestContext | None] = ContextVar("platform_request", default=None)


@contextmanager
def request_context(
    *,
    request_id: str | None = None,
    tenant_id: uuid.UUID | None = None,
    principal_id: str | None = None,
    access_token: str | None = None,
    traceparent: str | None = None,
    deadline: float | None = None,
) -> Iterator[RequestContext]:
    """调用者必须是认证/测试适配层；退出与异常均恢复原作用域。"""
    parent = current_context.get()
    identifier = (
        request_id
        if request_id and REQUEST_ID.fullmatch(request_id)
        else (parent.request_id if parent and request_id is None else uuid.uuid4().hex)
    )
    if parent and parent.tenant_id and tenant_id and parent.tenant_id != tenant_id:
        raise ValueError("嵌套调用不能切换租户")
    if parent and parent.principal_id and principal_id and parent.principal_id != principal_id:
        raise ValueError("嵌套调用不能切换用户身份")
    if parent:
        tenant_id = tenant_id or parent.tenant_id
        principal_id = principal_id or parent.principal_id
        access_token = access_token if access_token is not None else parent.access_token
    if parent and parent.deadline is not None:
        deadline = min(deadline, parent.deadline) if deadline is not None else parent.deadline
    valid_trace = (
        traceparent
        if traceparent and re.fullmatch(r"00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]", traceparent)
        else None
    )
    if valid_trace and (
        valid_trace.split("-")[1] == "0" * 32 or valid_trace.split("-")[2] == "0" * 16
    ):
        valid_trace = None
    context = RequestContext(
        identifier, tenant_id, principal_id, access_token, valid_trace, deadline
    )
    token = current_context.set(context)
    try:
        yield context
    finally:
        current_context.reset(token)


def request_deadline(default_seconds: float = 10) -> float:
    context = current_context.get()
    local = time.time() + default_seconds
    return min(local, context.deadline) if context and context.deadline is not None else local
