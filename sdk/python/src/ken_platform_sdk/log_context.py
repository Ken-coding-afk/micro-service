"""标准 logging 接入；只投影非敏感上下文，不读取/输出 access_token。"""

import json
import logging
from datetime import UTC, datetime

from .context import current_context


class ContextLogFilter(logging.Filter):
    def __init__(self, service: str):
        super().__init__()
        self.service = service

    def filter(self, record: logging.LogRecord) -> bool:
        context = current_context.get()
        record.service = self.service
        record.request_id = context.request_id if context else None
        record.tenant_id = str(context.tenant_id) if context and context.tenant_id else None
        record.trace_id = (
            context.traceparent.split("-")[1] if context and context.traceparent else None
        )
        return True


class ContextJsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        data = {
            "time": datetime.fromtimestamp(record.created, UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            **{
                name: getattr(record, name, None)
                for name in ("service", "request_id", "tenant_id", "trace_id")
            },
        }
        if record.exc_info and record.exc_info[0]:
            data["exception_type"] = record.exc_info[0].__name__
        return json.dumps(data, ensure_ascii=False)


def configure_logging(service: str) -> None:
    """进程入口调用；已有业务 handler 保留，补充字段，默认输出 JSON。"""
    root = logging.getLogger()
    if not root.handlers:
        default_handler = logging.StreamHandler()
        default_handler.setFormatter(ContextJsonFormatter())
        root.addHandler(default_handler)
    for logger in (root, logging.getLogger("uvicorn"), logging.getLogger("uvicorn.access")):
        for handler in logger.handlers:
            if not any(isinstance(f, ContextLogFilter) for f in handler.filters):
                handler.addFilter(ContextLogFilter(service))
