"""可选 db + mq 适配；业务自行提供事务、处理函数和调用周期，不启动后台 Worker。"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import aio_pika
from aio_pika.abc import AbstractChannel, AbstractIncomingMessage
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from .context import current_context, request_context
from .db import tenant_transaction

LOG = logging.getLogger(__name__)
RETRY_SECONDS = (5, 30, 120)
MAX_MESSAGE_BYTES = 1024 * 1024
FORBIDDEN_FIELDS = {"password", "access_token", "refresh_token", "client_secret", "callback_url"}


def _payload(value: Any, depth: int = 0) -> None:
    if depth > 32:
        raise ValueError("事件嵌套过深")
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or key.lower() in FORBIDDEN_FIELDS:
                raise ValueError("事件不能包含凭据或任意回调字段")
            _payload(item, depth + 1)
    elif isinstance(value, list):
        for item in value:
            _payload(item, depth + 1)


class Event(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    event_id: UUID
    event_type: str = Field(pattern=r"^[a-z][a-z0-9._-]{0,159}$")
    schema_version: int = Field(ge=1, le=2147483647, strict=True)
    tenant_id: UUID
    producer: str = Field(pattern=r"^[a-z][a-z0-9-]{0,63}$")
    occurred_at: datetime
    traceparent: str | None = None
    aggregate_id: str | None = Field(default=None, max_length=160)
    aggregate_version: int | None = Field(default=None, ge=0, le=9223372036854775807, strict=True)
    data: dict[str, Any]

    @field_validator("occurred_at", mode="before")
    @classmethod
    def utc_input(cls, value):
        if not isinstance(value, (datetime, str)):
            raise ValueError("事件时间必须为 UTC 字符串")  # noqa: TRY004 - Pydantic 校验错误需统一归为 ValidationError
        return value

    @model_validator(mode="after")
    def validate_event(self) -> Event:
        if self.occurred_at.utcoffset() != UTC.utcoffset(None):
            raise ValueError("事件时间必须为 UTC")
        if (self.aggregate_id is None) != (self.aggregate_version is None):
            raise ValueError("聚合标识与版本必须同时提供")
        if self.traceparent is not None and not re.fullmatch(
            r"00-(?!0{32}-)[0-9a-f]{32}-(?!0{16}-)[0-9a-f]{16}-0[01]", self.traceparent
        ):
            raise ValueError("traceparent 不合法")
        _payload(self.data)
        return self

    def encode(self) -> bytes:
        # frozen 不会冻结嵌套字典，因此在跨越持久化边界时重新校验。
        checked = Event.model_validate(self.model_dump())
        raw = json.dumps(
            checked.model_dump(mode="json"), ensure_ascii=False, allow_nan=False
        ).encode()
        if len(raw) > MAX_MESSAGE_BYTES:
            raise ValueError("事件超过大小上限")
        return raw

    @staticmethod
    def decode(raw: bytes) -> Event:
        if len(raw) > MAX_MESSAGE_BYTES:
            raise ValueError("事件超过大小上限")
        value = Event.model_validate_json(raw)
        value.encode()  # 同时拒绝 JSON NaN 等不兼容值
        return value


async def enqueue(session: AsyncSession, event: Event) -> None:
    """调用方的业务事务同时提交 Outbox；不能在此独立提交。"""
    context = current_context.get()
    if not session.in_transaction() or context is None or context.tenant_id != event.tenant_id:
        raise ValueError("Outbox 必须加入当前租户业务事务")
    tenant = (await session.execute(text("SELECT current_setting('app.tenant_id', true)"))).scalar()
    if tenant != str(event.tenant_id):
        raise ValueError("Outbox 数据库事务租户不一致")
    await session.execute(
        text(
            "INSERT INTO framework_outbox(event_id,tenant_id,producer,event_type,body) VALUES(:id,:tenant,:producer,:type,:body)"
        ),
        {
            "id": event.event_id,
            "tenant": event.tenant_id,
            "producer": event.producer,
            "type": event.event_type,
            "body": event.encode().decode(),
        },
    )


class RabbitEvents:
    """每条队列固定一个事件类型和生产服务；账号权限由项目资源配置限定。"""

    def __init__(
        self,
        channel: AbstractChannel,
        *,
        queue: str,
        producer: str,
        event_type: str,
        username: str,
        accepted_users: frozenset[str],
    ):
        if not re.fullmatch(r"[a-z][a-z0-9._-]{0,127}", queue) or not accepted_users:
            raise ValueError("必须提供固定队列与可信 Rabbit 用户")
        if not channel.publisher_confirms or not getattr(channel, "on_return_raises", False):
            raise ValueError("消息通道必须开启 publisher confirms")
        self.channel = channel
        self.queue = queue
        self.producer = producer
        self.event_type = event_type
        self.username = username
        self.accepted_users = accepted_users

    async def declare(self) -> None:
        """拓扑由受控初始化身份调用；不在普通 HTTP 启动时自动创建。"""
        for suffix in ("", ".quarantine", ".retry.1", ".retry.2", ".retry.3"):
            await self.channel.declare_exchange(
                self.queue + suffix, aio_pika.ExchangeType.DIRECT, durable=True
            )
        await self.channel.declare_queue(
            self.queue + ".quarantine",
            durable=True,
            arguments={"x-queue-type": "quorum", "x-delivery-limit": -1},
        )
        await self.channel.declare_queue(
            self.queue,
            durable=True,
            arguments={
                "x-queue-type": "quorum",
                "x-delivery-limit": 20,
                "x-dead-letter-exchange": self.queue + ".quarantine",
                "x-dead-letter-routing-key": self.queue + ".quarantine",
                "x-dead-letter-strategy": "at-least-once",
                "x-overflow": "reject-publish",
            },
        )
        for index, delay in enumerate(RETRY_SECONDS, 1):
            await self.channel.declare_queue(
                f"{self.queue}.retry.{index}",
                durable=True,
                arguments={
                    "x-queue-type": "quorum",
                    "x-message-ttl": delay * 1000,
                    "x-dead-letter-exchange": self.queue,
                    "x-dead-letter-routing-key": self.queue,
                    "x-dead-letter-strategy": "at-least-once",
                    "x-overflow": "reject-publish",
                },
            )

        for suffix in ("", ".quarantine", ".retry.1", ".retry.2", ".retry.3"):
            name = self.queue + suffix
            queue = await self.channel.get_queue(name)
            await queue.bind(name, routing_key=name)

    async def publish(self, raw: bytes, destination: str | None = None, attempt: int = 0) -> None:
        # 使用 on_return_raises=True 创建通道；mandatory return/confirm 超时均使调用失败。
        exchange = await self.channel.get_exchange(destination or self.queue, ensure=False)
        confirmation = await exchange.publish(
            aio_pika.Message(
                raw,
                content_type="application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                user_id=self.username,
                headers={"x-framework-attempt": attempt},
            ),
            routing_key=destination or self.queue,
            mandatory=True,
            timeout=5,
        )
        from pamqp.commands import Basic

        if not isinstance(confirmation, Basic.Ack):
            raise RuntimeError("消息未得到肯定发布确认")  # noqa: TRY004 - 这是发布失败，不是调用参数类型错误

    async def dispatch_one(self, factory: async_sessionmaker[AsyncSession], tenant: UUID) -> bool:
        """单次有界投递；服务自己决定轮询周期和获准处理的租户，不扫描其他租户。"""
        with request_context(tenant_id=tenant):
            async with tenant_transaction(factory) as session:
                row = (
                    await session.execute(
                        text(
                            "SELECT event_id,body FROM framework_outbox WHERE tenant_id=:tenant AND producer=:producer AND event_type=:type AND delivered_at IS NULL ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED"
                        ),
                        {"tenant": tenant, "producer": self.producer, "type": self.event_type},
                    )
                ).first()
                if row is None:
                    return False
                event = Event.decode(row.body.encode())
                if event.producer != self.producer or event.event_type != self.event_type:
                    raise ValueError("Outbox 事件不属于当前投递通道")
                await self.publish(row.body.encode())
                await session.execute(
                    text(
                        "UPDATE framework_outbox SET delivered_at=now() WHERE tenant_id=:tenant AND event_id=:id"
                    ),
                    {"tenant": tenant, "id": row.event_id},
                )
                return True

    async def consume(
        self,
        message: AbstractIncomingMessage,
        factory: async_sessionmaker[AsyncSession],
        consumer_name: str,
        handler: Callable[[AsyncSession, Event], Awaitable[None]],
        supported_versions: frozenset[int] = frozenset({1}),
    ) -> str:
        if not re.fullmatch(r"[a-z][a-z0-9._-]{0,127}", consumer_name):
            raise ValueError("consumer_name 不合法")
        attempt = message.headers.get("x-framework-attempt", 0)
        if type(attempt) is not int or not 0 <= attempt <= len(RETRY_SECONDS):
            return await self._move(message, "quarantine", 0)
        try:
            event = Event.decode(message.body)
            if (
                message.user_id not in self.accepted_users
                or event.producer != self.producer
                or event.event_type != self.event_type
                or event.schema_version not in supported_versions
            ):
                raise ValueError("未知事件或消息来源不在队列边界")
        except (ValueError, TypeError):
            return await self._move(message, "quarantine", attempt)
        try:
            with request_context(tenant_id=event.tenant_id, traceparent=event.traceparent):
                async with tenant_transaction(factory) as session:
                    digest = hashlib.sha256(message.body).hexdigest()
                    inserted = (
                        await session.execute(
                            text(
                                "INSERT INTO framework_inbox(consumer_name,event_id,tenant_id,body_digest) VALUES(:consumer,:id,:tenant,:digest) ON CONFLICT (consumer_name,event_id) DO NOTHING RETURNING event_id"
                            ),
                            {
                                "consumer": consumer_name,
                                "id": event.event_id,
                                "tenant": event.tenant_id,
                                "digest": digest,
                            },
                        )
                    ).scalar_one_or_none()
                    if inserted:
                        await handler(session, event)
                    else:
                        prior = (
                            await session.execute(
                                text(
                                    "SELECT body_digest FROM framework_inbox WHERE consumer_name=:consumer AND event_id=:id AND tenant_id=:tenant"
                                ),
                                {
                                    "consumer": consumer_name,
                                    "id": event.event_id,
                                    "tenant": event.tenant_id,
                                },
                            )
                        ).scalar_one_or_none()
                        if prior != digest:
                            raise ValueError("事件标识与已处理内容冲突")
        except Exception:  # noqa: BLE001 - 业务回调任意异常都须回滚，进入有界重试
            destination = f"retry.{attempt + 1}" if attempt < len(RETRY_SECONDS) else "quarantine"
            return await self._move(message, destination, min(attempt + 1, len(RETRY_SECONDS)))
        # ack 丢失可重投，但已提交 Inbox 会拦住重复业务副作用。
        await message.ack()
        return "processed" if inserted else "duplicate"

    async def _move(self, message: AbstractIncomingMessage, suffix: str, attempt: int) -> str:
        try:
            await self.publish(message.body, f"{self.queue}.{suffix}", attempt)
        except Exception:
            # 不 ack、不热循环 requeue；关闭消费通道交还未确认消息，由连接层退避恢复。
            await message.channel.close()
            raise
        await message.ack()
        if suffix == "quarantine":
            LOG.error("event_quarantined queue=%s reason=invalid_or_exhausted", self.queue)
        return suffix
