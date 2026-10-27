"""进程内非等待准入；计数交给 AnyIO，不提供分布式资源调度。"""

from contextlib import contextmanager

from anyio import CapacityLimiter, WouldBlock

from .errors import PlatformError


@contextmanager
def admission(
    limiter: CapacityLimiter | None, *, inbound: bool = False, metrics=None, resource="http.inbound"
):
    borrower = object()
    if limiter is not None:
        try:
            limiter.acquire_on_behalf_of_nowait(borrower)
        except WouldBlock:
            if metrics:
                metrics.reject(resource, "concurrency")
            raise PlatformError(
                "SERVICE_BUSY" if inbound else "DEPENDENCY_BUSY", "服务容量已满", 503
            ) from None
    try:
        yield
    finally:
        if limiter is not None:
            limiter.release_on_behalf_of(borrower)
