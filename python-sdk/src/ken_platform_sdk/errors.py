"""跨语言稳定错误；消息不包含凭据、连接串或上游响应正文。"""


class PlatformError(Exception):
    def __init__(
        self, code: str, message: str, status: int = 503, *, headers: dict[str, str] | None = None
    ):
        super().__init__(message)
        self.code = code
        self.status = status
        self.headers = dict(headers or {})


class DependencyUnavailable(PlatformError):
    def __init__(self, message: str = "依赖暂时不可用"):
        super().__init__("DEPENDENCY_UNAVAILABLE", message, 503)


class DeadlineExceeded(PlatformError):
    def __init__(self):
        super().__init__("UPSTREAM_TIMEOUT", "调用预算已耗尽；写入结果可能需要核对", 504)
