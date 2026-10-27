import asyncio
import json
import os
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import aio_pika
import pytest
from sqlalchemy import URL, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from ken_platform_sdk.context import request_context
from ken_platform_sdk.db import tenant_transaction
from ken_platform_sdk.messaging import Event, RabbitEvents, enqueue


def event(tenant, **values):
    return Event(
        event_id=uuid4(),
        event_type="sample.changed",
        schema_version=1,
        tenant_id=tenant,
        producer="sample-service",
        occurred_at=datetime.now(UTC),
        data={"amount": 1},
        **values,
    )


def test_event_rejects_credentials_invalid_versions_and_oversize():
    value = event(uuid4())
    assert Event.decode(value.encode()) == value
    for update in [
        {"schema_version": True},
        {"schema_version": "1"},
        {"data": {"nested": {"access_token": "not-allowed"}}},
        {"occurred_at": datetime.now()},
        {"aggregate_id": "item"},
    ]:
        with pytest.raises(ValueError):
            Event.model_validate({**value.model_dump(), **update})
    value.data["client_secret"] = "mutated"
    with pytest.raises(ValueError):
        value.encode()
    with pytest.raises(ValueError):
        Event.decode(b"x" * (1024 * 1024 + 1))


@pytest.fixture
async def live():
    if not os.getenv("PLATFORM_TEST_RABBIT_BINDING") or not os.getenv(
        "PLATFORM_TEST_DATABASE_BINDING"
    ):
        pytest.skip("需要隔离 RabbitMQ/PG 绑定")
    mq = json.loads(Path(os.environ["PLATFORM_TEST_RABBIT_BINDING"]).read_text())
    db = json.loads(Path(os.environ["PLATFORM_TEST_DATABASE_BINDING"]).read_text())
    assert mq["test_only"] and db["test_only"] and db["dbname"].startswith("t02t")
    assert mq["host"] in {"localhost", "127.0.0.1"}
    connection = await aio_pika.connect(
        host=mq["host"],
        port=mq["port"],
        login=mq["username"],
        password=Path(mq["password_file"]).read_text().strip(),
        virtualhost=mq["vhost"],
    )
    channel = await connection.channel(publisher_confirms=True, on_return_raises=True)
    await channel.set_qos(prefetch_count=1)
    engine = create_async_engine(
        URL.create(
            "postgresql+psycopg",
            username=db["username"],
            password=Path(db["password_file"]).read_text().strip(),
            host=db["host"],
            port=db["port"],
            database=db["dbname"],
        ),
        pool_size=2,
    )
    factory = async_sessionmaker(engine, expire_on_commit=False)
    events = RabbitEvents(
        channel,
        queue="slim-test-" + uuid4().hex,
        producer="sample-service",
        event_type="sample.changed",
        username=mq["username"],
        accepted_users=frozenset({mq["username"]}),
    )
    await events.declare()
    try:
        yield events, channel, factory, uuid4()
    finally:
        if not channel.is_closed:
            for suffix in ["", ".quarantine", ".retry.1", ".retry.2", ".retry.3"]:
                queue = await channel.get_queue(events.queue + suffix)
                await queue.delete(if_unused=False, if_empty=False)
                await channel.exchange_delete(events.queue + suffix)
        await connection.close()
        await engine.dispose()


async def take(channel, name):
    queue = await channel.get_queue(name)
    async with asyncio.timeout(12):
        while True:
            delivery = await queue.get(fail=False)
            if delivery is not None:
                return delivery
            await asyncio.sleep(0.1)


async def effect(session, item):
    await session.execute(
        text("INSERT INTO slim_message_effects(event_id,tenant_id,amount) VALUES(:id,:tenant,1)"),
        {"id": item.event_id, "tenant": item.tenant_id},
    )


async def count(factory, tenant, item):
    with request_context(tenant_id=tenant):
        async with tenant_transaction(factory) as session:
            return (
                await session.execute(
                    text("SELECT count(*) FROM slim_message_effects WHERE event_id=:id"),
                    {"id": item.event_id},
                )
            ).scalar_one()


async def test_outbox_rollback_confirm_loss_and_duplicate_effect(live):
    events, channel, factory, tenant = live
    aborted, value = event(tenant), event(tenant)
    with request_context(tenant_id=tenant), pytest.raises(RuntimeError):
        async with tenant_transaction(factory) as session:
            await enqueue(session, aborted)
            raise RuntimeError("business rollback")
    assert not await events.dispatch_one(factory, tenant)
    with request_context(tenant_id=tenant):
        async with tenant_transaction(factory) as session:
            await enqueue(session, value)
    send = events.publish

    async def uncertain(*args, **kwargs):
        await send(*args, **kwargs)
        raise RuntimeError("confirm lost before database commit")

    events.publish = uncertain
    with pytest.raises(RuntimeError):
        await events.dispatch_one(factory, tenant)
    events.publish = send
    assert await events.dispatch_one(factory, tenant)
    assert not await events.dispatch_one(factory, tenant)
    assert (
        await events.consume(await take(channel, events.queue), factory, "sample-consumer", effect)
        == "processed"
    )
    assert (
        await events.consume(await take(channel, events.queue), factory, "sample-consumer", effect)
        == "duplicate"
    )
    assert await count(factory, tenant, value) == 1
    assert await count(factory, uuid4(), value) == 0


async def test_failure_rolls_back_then_delayed_retry_and_exhaustion_quarantines(live):
    events, channel, factory, tenant = live
    value = event(tenant)
    await events.publish(value.encode())

    async def interrupted(session, item):
        await effect(session, item)
        raise RuntimeError("consumer interrupted before commit")

    assert (
        await events.consume(
            await take(channel, events.queue), factory, "sample-consumer", interrupted
        )
        == "retry.1"
    )
    assert await count(factory, tenant, value) == 0
    assert (
        await events.consume(await take(channel, events.queue), factory, "sample-consumer", effect)
        == "processed"
    )
    assert await count(factory, tenant, value) == 1
    exhausted = event(tenant)
    await events.publish(exhausted.encode(), attempt=3)
    assert (
        await events.consume(
            await take(channel, events.queue), factory, "sample-consumer", interrupted
        )
        == "quarantine"
    )
    quarantine = await take(channel, events.queue + ".quarantine")
    assert Event.decode(quarantine.body).event_id == exhausted.event_id
    await quarantine.ack()
    assert await count(factory, tenant, exhausted) == 0


async def test_unknown_schema_and_changed_duplicate_payload_never_execute(live):
    events, channel, factory, tenant = live
    value = event(tenant)
    unknown = value.model_copy(update={"schema_version": 2})
    await events.publish(unknown.encode())
    assert (
        await events.consume(await take(channel, events.queue), factory, "sample-consumer", effect)
        == "quarantine"
    )
    assert await count(factory, tenant, value) == 0
    await events.publish(value.encode())
    assert (
        await events.consume(await take(channel, events.queue), factory, "sample-consumer", effect)
        == "processed"
    )
    changed = value.model_copy(update={"data": {"amount": 2}})
    await events.publish(changed.encode(), attempt=3)
    assert (
        await events.consume(await take(channel, events.queue), factory, "sample-consumer", effect)
        == "quarantine"
    )
    assert await count(factory, tenant, value) == 1


async def test_unroutable_is_failure_and_unconfirmed_retry_does_not_ack_original(live):
    events, channel, factory, tenant = live
    empty = events.queue + ".empty"
    await channel.declare_exchange(empty, aio_pika.ExchangeType.DIRECT, durable=False)
    with pytest.raises(aio_pika.exceptions.DeliveryError):
        await events.publish(event(tenant).encode(), empty)
    await channel.exchange_delete(empty)
    await events.publish(event(tenant).model_copy(update={"schema_version": 2}).encode())
    message = await take(channel, events.queue)

    async def unavailable(*args, **kwargs):
        raise RuntimeError("publisher unavailable")

    events.publish = unavailable
    with pytest.raises(RuntimeError):
        await events.consume(message, factory, "sample-consumer", effect)
    # 真正关闭消费通道；原消息未 ack，应在新通道重新出现。
    assert channel.is_closed
    replacement = await channel._connection.channel(publisher_confirms=True, on_return_raises=True)
    recovered = await take(replacement, events.queue)
    assert recovered.redelivered
    await recovered.ack()
    await replacement.close()


async def test_concurrent_duplicates_commit_once_and_untrusted_publisher_is_quarantined(live):
    events, channel, factory, tenant = live
    value = event(tenant)
    await events.publish(value.encode())
    await events.publish(value.encode())
    first, second = await take(channel, events.queue), await take(channel, events.queue)
    outcomes = await asyncio.gather(
        events.consume(first, factory, "replicas", effect),
        events.consume(second, factory, "replicas", effect),
    )
    assert set(outcomes) == {"processed", "duplicate"}
    assert await count(factory, tenant, value) == 1
    events.accepted_users = frozenset({"other-service"})
    foreign = event(tenant)
    await events.publish(foreign.encode())
    assert (
        await events.consume(await take(channel, events.queue), factory, "replicas", effect)
        == "quarantine"
    )
    assert await count(factory, tenant, foreign) == 0
