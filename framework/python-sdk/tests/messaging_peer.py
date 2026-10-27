"""仅供 Java 定向测试调用：从真实 Rabbit 接收 Java JSON，再回送 Python JSON。"""

import asyncio
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID, uuid4

import aio_pika
from sqlalchemy import URL, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from ken_platform_sdk.db import database_loop_factory
from ken_platform_sdk.messaging import Event, RabbitEvents


async def main():
    mq = json.loads(Path(os.environ["PLATFORM_TEST_RABBIT_BINDING"]).read_text())
    db = json.loads(Path(os.environ["PLATFORM_TEST_DATABASE_BINDING"]).read_text())
    assert mq["test_only"] and db["test_only"] and db["dbname"].startswith("t02t")
    connection = await aio_pika.connect(
        host=mq["host"],
        port=mq["port"],
        login=mq["username"],
        password=Path(mq["password_file"]).read_text().strip(),
        virtualhost=mq["vhost"],
    )
    channel = await connection.channel(publisher_confirms=True, on_return_raises=True)
    engine = create_async_engine(
        URL.create(
            "postgresql+psycopg",
            username=db["username"],
            password=Path(db["password_file"]).read_text().strip(),
            host=db["host"],
            port=db["port"],
            database=db["dbname"],
        )
    )
    adapter = RabbitEvents(
        channel,
        queue=sys.argv[1],
        producer="sample-service",
        event_type="sample.changed",
        username=mq["username"],
        accepted_users=frozenset({mq["username"]}),
    )
    try:
        queue = await channel.get_queue(sys.argv[1])
        message = await queue.get()
        expected = UUID(sys.argv[2])

        async def effect(session, event):
            assert event.event_id == expected
            await session.execute(
                text(
                    "INSERT INTO slim_message_effects(event_id,tenant_id,amount) VALUES(:id,:tenant,1)"
                ),
                {"id": event.event_id, "tenant": event.tenant_id},
            )

        result = await adapter.consume(
            message, async_sessionmaker(engine, expire_on_commit=False), "python-peer", effect
        )
        assert result == "processed"
        source = Event.decode(message.body)
        reply = Event(
            event_id=uuid4(),
            event_type="sample.changed",
            schema_version=1,
            tenant_id=source.tenant_id,
            producer="sample-service",
            occurred_at=datetime.now(UTC),
            data={"source": "python"},
        )
        await adapter.publish(reply.encode())
        print("Java to Python message committed; Python reply confirmed")
    finally:
        await connection.close()
        await engine.dispose()


if __name__ == "__main__":
    with asyncio.Runner(loop_factory=database_loop_factory) as runner:
        runner.run(main())
