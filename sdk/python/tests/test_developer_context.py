import asyncio
import io
import json
import logging
import time
import uuid

import pytest

from ken_platform_sdk.context import (
    check_deadline,
    current_context,
    remaining_seconds,
    request_context,
    task_context,
)
from ken_platform_sdk.errors import DeadlineExceeded
from ken_platform_sdk.log_context import ContextJsonFormatter, ContextLogFilter


def test_exception_formatter_retains_safe_location_without_exception_secrets():
    logger = logging.getLogger("safe-stack-fixture")
    output = io.StringIO()
    handler = logging.StreamHandler(output)
    handler.setFormatter(ContextJsonFormatter())
    handler.addFilter(ContextLogFilter("fixture"))
    logger.addHandler(handler)
    try:
        try:
            raise RuntimeError("password=private-body")
        except RuntimeError:
            logger.exception("处理失败")
        row = json.loads(output.getvalue())
        assert row["exception_type"] == "RuntimeError"
        frame = row["exception_frames"][-1]
        assert (
            frame["function"]
            == "test_exception_formatter_retains_safe_location_without_exception_secrets"
        )
        assert frame["file"] == "test_developer_context.py" and frame["line"] > 0
        assert "private-body" not in output.getvalue()
        assert set(frame) == {"file", "function", "line"}
    finally:
        logger.removeHandler(handler)


async def test_error_log_keeps_authenticated_tenant_after_yield_dependency_exits():
    import httpx
    from fastapi import Depends, FastAPI, Request

    from ken_platform_sdk.web import PlatformMiddleware, install_error_handlers

    app = FastAPI()
    app.add_middleware(PlatformMiddleware)
    install_error_handlers(app)
    tenants = {"first": uuid.uuid4(), "second": uuid.uuid4()}

    async def authenticated(request: Request):
        # 模拟鉴权完成后的可信主体，忽略请求者提供的租户头。
        with request_context(tenant_id=tenants[request.path_params["name"]]) as context:
            request.state.platform_context = context
            yield context

    @app.get("/api/failure/{name}", dependencies=[Depends(authenticated)])
    async def failure(name: str):
        await asyncio.sleep(0)
        raise RuntimeError("private-secret")

    logger = logging.getLogger("ken_platform_sdk.web")
    output = io.StringIO()
    handler = logging.StreamHandler(output)
    handler.addFilter(ContextLogFilter("fixture"))
    handler.setFormatter(ContextJsonFormatter())
    logger.addHandler(handler)
    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            responses = await asyncio.gather(
                *(
                    client.get(
                        "/api/failure/" + name,
                        headers={"X-Request-Id": name, "X-Tenant-Id": "forged"},
                    )
                    for name in tenants
                )
            )
        assert all(response.status_code == 500 for response in responses)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        assert len(rows) == 2
        for row in rows:
            assert row["tenant_id"] == str(tenants[row["request_id"]])
            assert row["exception_frames"][-1]["function"] == "failure"
        assert "private-secret" not in output.getvalue()
        assert current_context.get() is None
    finally:
        logger.removeHandler(handler)


async def test_log_records_follow_concurrent_contexts_and_to_thread_without_credentials():
    output = io.StringIO()
    handler = logging.StreamHandler(output)
    handler.addFilter(ContextLogFilter("inspection-service"))
    handler.setFormatter(ContextJsonFormatter())
    logger = logging.getLogger("developer-context-fixture")
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False
    tenants = [uuid.uuid4(), uuid.uuid4()]

    async def work(tenant):
        with request_context(
            tenant_id=tenant, request_id=str(tenant), access_token="private-bearer"
        ):
            await asyncio.sleep(0)
            await asyncio.to_thread(logger.info, "worker")
            logger.info("request")
        assert current_context.get() is None

    try:
        await asyncio.gather(*(work(t) for t in tenants))
        logger.info("outside")
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        assert len(rows) == 5
        assert all(row["tenant_id"] == row["request_id"] for row in rows[:4])
        assert {row["tenant_id"] for row in rows[:4]} == {str(t) for t in tenants}
        assert rows[-1]["tenant_id"] is None and rows[-1]["request_id"] is None
        assert "private-bearer" not in output.getvalue()
    finally:
        logger.removeHandler(handler)


def test_task_and_deadline_use_explicit_identity_and_restore_after_exception():
    assert current_context.get() is None
    with (
        pytest.raises(RuntimeError),
        task_context(
            tenant_id=uuid.uuid4(), service="inspection-service", timeout_seconds=5
        ) as context,
    ):
        assert context.principal_id == "service:inspection-service"
        assert context.access_token is None
        assert 0 < remaining_seconds(3) <= 3
        check_deadline()
        with (
            pytest.raises(ValueError),
            task_context(tenant_id=uuid.uuid4(), service="other", timeout_seconds=1),
        ):
            pass
        raise RuntimeError("fixture")
    assert current_context.get() is None
    with request_context(deadline=time.time() - 1):
        with pytest.raises(DeadlineExceeded):
            check_deadline()
        with pytest.raises(DeadlineExceeded):
            remaining_seconds()
    check_deadline()
    for budget in (0, float("inf"), float("nan"), 86401):
        with (
            pytest.raises(ValueError),
            task_context(
                tenant_id=uuid.uuid4(), service="inspection-service", timeout_seconds=budget
            ),
        ):
            pass


async def test_detached_task_requires_clean_context():
    from contextvars import Context

    async def work():
        with task_context(tenant_id=uuid.uuid4(), service="worker", timeout_seconds=5):
            assert current_context.get().access_token is None

    with request_context(access_token="request-bearer"):
        with pytest.raises(ValueError):
            await asyncio.create_task(work())
        await asyncio.create_task(work(), context=Context())
