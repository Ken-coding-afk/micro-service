"""标准 logging 接入；只投影非敏感上下文，不读取/输出 access_token。"""

import json
import logging
from datetime import UTC, datetime
from pathlib import Path

from .context import current_context


def exception_frames(error: BaseException) -> list[dict]:
    """保留定位所需帧，不格式化异常正文、源码行或局部变量。"""
    result: list[dict] = []
    trace = error.__traceback__
    while trace is not None and len(result) < 32:
        result.append(
            {
                "file": Path(trace.tb_frame.f_code.co_filename).name,
                "function": trace.tb_frame.f_code.co_name,
                "line": trace.tb_lineno,
            }
        )
        trace = trace.tb_next
    return result


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


class SafeAccessLogFilter(logging.Filter):
    """在所有 handler 格式化前移除 Uvicorn 查询串，未知记录形状拒绝输出。"""

    def filter(self, record: logging.LogRecord) -> bool:
        if record.name != "uvicorn.access":
            return True
        args = record.args
        if (
            record.msg != '%s - "%s %s HTTP/%s" %d'
            or not isinstance(args, tuple)
            or len(args) != 5
            or not isinstance(args[2], str)
        ):
            return False
        record.args = (*args[:2], args[2].partition("?")[0], *args[3:])
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
        if record.exc_info and record.exc_info[1]:
            data["exception_type"] = type(record.exc_info[1]).__name__
            data["exception_frames"] = exception_frames(record.exc_info[1])
        elif hasattr(record, "exception_frames"):
            data["exception_type"] = getattr(record, "exception_type", None)
            data["exception_frames"] = record.exception_frames
        return json.dumps(data, ensure_ascii=False)


def configure_logging(service: str) -> None:
    """进程入口调用；已有业务 handler 保留，补充字段，默认输出 JSON。"""
    access_logger = logging.getLogger("uvicorn.access")
    if not any(isinstance(f, SafeAccessLogFilter) for f in access_logger.filters):
        access_logger.addFilter(SafeAccessLogFilter())
    root = logging.getLogger()
    if not root.handlers:
        default_handler = logging.StreamHandler()
        default_handler.setFormatter(ContextJsonFormatter())
        root.addHandler(default_handler)
    for logger in (root, logging.getLogger("uvicorn"), logging.getLogger("uvicorn.access")):
        for handler in logger.handlers:
            if not any(isinstance(f, ContextLogFilter) for f in handler.filters):
                handler.addFilter(ContextLogFilter(service))
