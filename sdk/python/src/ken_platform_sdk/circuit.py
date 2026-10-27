"""出站依赖的薄适配；状态转换由 circuitbreaker，探测许可由 AnyIO 管理。"""

from anyio import CapacityLimiter, WouldBlock
from circuitbreaker import CircuitBreaker  # type: ignore[import-untyped]

from .errors import PlatformError


class RemoteFailure(Exception):
    """只用于给组件统计最终远端失败，不向业务暴露原文。"""


class DependencyCircuit(CircuitBreaker):
    def __init__(self, name, parameters, metrics):
        super().__init__(
            name=name,
            failure_threshold=parameters.failure_threshold,
            recovery_timeout=parameters.recovery_timeout_seconds,
            expected_exception=RemoteFailure,
        )
        self.probe = CapacityLimiter(1)
        self.metrics = metrics
        self.generation = 0

    def __exit__(self, kind, error, traceback):
        # 父类会把未计数异常当成功 reset；取消/本地失败应保持状态。
        if kind is not None and not issubclass(kind, RemoteFailure):
            return False
        return super().__exit__(kind, error, traceback)

    async def execute(self, operation):
        borrower = object()
        held = False
        if self.opened:
            self.reject()
        if self.state == "half_open":
            try:
                self.probe.acquire_on_behalf_of_nowait(borrower)
                held = True
            except WouldBlock:
                self.reject()
        generation = self.generation
        try:
            result = await operation()
        except BaseException as failure:
            if generation == self.generation:
                self.__exit__(type(failure), failure, failure.__traceback__)
                if self.opened:
                    self.generation += 1
            raise
        else:
            # 已开始的旧请求不得在另一请求开路后把断路器关闭。
            if generation == self.generation:
                self.__exit__(None, None, None)
            return result
        finally:
            if held:
                self.probe.release_on_behalf_of(borrower)

    def reject(self):
        self.metrics.reject("http.outbound/" + self.name, "DEPENDENCY_CIRCUIT_OPEN")
        raise PlatformError("DEPENDENCY_CIRCUIT_OPEN", "依赖熔断，稍后重试", 503)
