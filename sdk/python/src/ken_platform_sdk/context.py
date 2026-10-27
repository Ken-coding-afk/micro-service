"""请求作用域上下文；身份由认证适配器提供，不从客户端身份 header 推导。"""

from __future__ import annotations

import math
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


def remaining_seconds(default_seconds: float = 10) -> float:
    """剩余预算供业务传给成熟客户端；不会终止已提交的写入。"""
    from .errors import DeadlineExceeded

    if not math.isfinite(default_seconds) or default_seconds <= 0:
        raise ValueError("默认预算必须是有限正数")
    remaining = request_deadline(default_seconds) - time.time()
    if remaining <= 0:
        raise DeadlineExceeded()
    return remaining


def check_deadline() -> None:
    """协作检查点；无 deadline 时不人为给后台工作增加 HTTP 预算。"""
    from .errors import DeadlineExceeded

    context = current_context.get()
    if context and context.deadline is not None and context.deadline <= time.time():
        raise DeadlineExceeded()


@contextmanager
def task_context(*, tenant_id: uuid.UUID, service: str, timeout_seconds: float):
    """仅供验证过来源的任务入口；不是认证/授权接口，不继承请求令牌。"""
    if current_context.get() is not None:
        raise ValueError("独立任务必须从干净上下文启动")
    if not isinstance(tenant_id, uuid.UUID) or not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", service):
        raise ValueError("任务必须声明合法租户与服务")
    if not math.isfinite(timeout_seconds) or not 0 < timeout_seconds <= 86400:
        raise ValueError("任务预算必须大于零且不超过24小时")
    with request_context(
        tenant_id=tenant_id,
        principal_id="service:" + service,
        deadline=time.time() + timeout_seconds,
    ) as context:
        yield context
