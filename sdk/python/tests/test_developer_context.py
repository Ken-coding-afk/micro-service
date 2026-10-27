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
