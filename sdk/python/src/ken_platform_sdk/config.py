"""类型化应用配置；环境/秘密与 Nacos 运行参数分别加载，不接受远程覆盖。"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import re
from collections.abc import Mapping
from pathlib import Path
from types import MappingProxyType
from typing import Any, Literal, Self

import yaml
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    field_serializer,
    field_validator,
    model_validator,
)

from .discovery import ServiceInstance


class CircuitParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    enabled: bool = False
    failure_threshold: int = Field(default=5, gt=0, le=1000)
    recovery_timeout_seconds: int = Field(default=30, gt=0, le=3600)


class HttpParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    connect_timeout_seconds: float = Field(default=2.0, gt=0, le=10)
    response_budget_seconds: float = Field(default=10.0, gt=0, le=300)
    get_retry_extra: Literal[0, 1] = 1
    max_connections: int = Field(default=64, gt=0)
    max_keepalive_connections: int = Field(default=32, gt=0)
    circuit_breakers: Mapping[str, CircuitParameters] = Field(
        default_factory=dict, validate_default=True
    )

    @field_validator("circuit_breakers")
    @classmethod
    def valid_circuits(cls, value):
        if len(value) > 64 or any(not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", key) for key in value):
            raise ValueError("熔断规则必须使用有限逻辑服务名")
        return MappingProxyType(dict(value))

    @field_serializer("circuit_breakers")
    def serialize_circuits(self, value):
        return {key: rule.model_dump() for key, rule in value.items()}

    @model_validator(mode="after")
    def bounded_pool(self) -> Self:
        if self.max_keepalive_connections > self.max_connections:
            raise ValueError("keepalive 连接数不能超过总连接数")
        return self

    @field_validator("get_retry_extra", mode="before")
    @classmethod
    def exact_retry_count(cls, value):
        if type(value) is not int:
            raise ValueError("重试次数必须是整数 0 或 1")
        return value


class ObservationParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    metrics_enabled: bool = True


class ConcurrencyParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    inbound_limit: int | None = Field(default=None, gt=0)
    outbound_limits: Mapping[str, int] = Field(default_factory=dict, validate_default=True)

    @field_validator("outbound_limits")
    @classmethod
    def valid_limits(cls, value):
        if len(value) > 64 or any(
            not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", name) or type(limit) is not int or limit <= 0
            for name, limit in value.items()
        ):
            raise ValueError("出站并发声明必须是有限逻辑服务名和正整数")
        return MappingProxyType(dict(value))

    @field_serializer("outbound_limits")
    def serialize_limits(self, value):
        return dict(value)


class RuntimeParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    log_level: Literal["DEBUG", "INFO", "WARNING", "WARN", "ERROR"] = "INFO"
    http: HttpParameters = HttpParameters()
    observation: ObservationParameters = ObservationParameters()
    concurrency: ConcurrencyParameters = Field(default_factory=ConcurrencyParameters)
    business: Mapping[str, Any] = Field(default_factory=dict, validate_default=True)

    @field_validator("business", mode="after")
    @classmethod
    def immutable_business(cls, value):
        def freeze(item):
            if isinstance(item, Mapping):
                for key in item:
                    if not isinstance(key, str) or not re.fullmatch(r"[a-z][a-z0-9_]*", key):
                        raise ValueError("业务配置字段必须为小写标识符")
                    if re.search(
                        r"(^|_)(password|secret|token|api_key|private_key)($|_)", key
                    ) or key in {
                        "project_code",
                        "app_env",
                        "public_origin",
                        "service_name",
                        "pg_host",
                        "pg_port",
                        "db_name",
                        "db_user",
                        "redis_host",
                        "redis_port",
                        "nacos_server_addr",
                        "nacos_namespace",
                    }:
                        raise ValueError("业务运行配置不能携带秘密或环境绑定")
                return MappingProxyType({key: freeze(child) for key, child in item.items()})
            if isinstance(item, list):
                return tuple(freeze(child) for child in item)
            if item is None or type(item) in (str, int, float, bool):
                return item
            raise ValueError("业务配置仅支持 JSON 值")

        return freeze(value)

    @field_serializer("business")
    def business_values(self, value):
        def plain(item):
            if isinstance(item, Mapping):
                return {key: plain(child) for key, child in item.items()}
            if isinstance(item, tuple):
                return [plain(child) for child in item]
            return item

        return plain(value)

    @property
    def digest(self) -> str:
        value = json.dumps(self.model_dump(), sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(value.encode()).hexdigest()


class _UniqueLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        pairs = self.construct_pairs(node, deep=deep)
        result = {}
        for key, value in pairs:
            if not isinstance(key, str) or key in result:
                raise ValueError("运行配置字段名必须是唯一字符串")
            result[key] = value
        return result


def load_runtime(shared: str, service: str) -> RuntimeParameters:
    """完整候选快照先校验再返回；调用方只在成功后替换活动快照。"""
    documents = []
    for source in (shared, service):
        if not isinstance(source, str) or not source.strip():
            raise ValueError("必需 Nacos 配置缺失")
        try:
            value = yaml.load(source, Loader=_UniqueLoader)
        except yaml.YAMLError:
            raise ValueError("运行配置 YAML 不合法") from None
        if not isinstance(value, dict):
            raise ValueError("必需 Nacos 配置缺失或不是 mapping")  # noqa: TRY004 - 对外统一配置错误
        documents.append(value)
    common, specific = documents
    if "business" in common or "concurrency" in common:
        raise ValueError("共享配置不承载业务字段或服务并发声明")
    allow_override = common.pop("allow_override", [])
    if not isinstance(allow_override, list) or not all(
        isinstance(key, str) for key in allow_override
    ):
        raise ValueError("allow_override 必须为字段名数组")

    def leaves(mapping, prefix=""):
        result = set()
        for key, value in mapping.items():
            name = f"{prefix}.{key}" if prefix else key
            result.update(leaves(value, name) if isinstance(value, dict) else {name})
        return result

    if not set(allow_override).issubset(leaves(common)):
        raise ValueError("allow_override 引用了不存在的共享字段")

    def merge(base: dict, overlay: dict, prefix: str = "") -> dict:
        result = dict(base)
        for key, value in overlay.items():
            name = f"{prefix}.{key}" if prefix else key
            if key in result and isinstance(result[key], dict) and isinstance(value, dict):
                result[key] = merge(result[key], value, name)
            elif key in result and name not in allow_override:
                raise ValueError(f"服务配置无权覆盖共享参数: {name}")
            else:
                result[key] = value
        return result

    try:
        return RuntimeParameters.model_validate(merge(common, specific))
    except ValueError:
        raise ValueError("运行配置字段类型、范围或来源不合法") from None


class PlatformSettings(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    project_code: str = Field(pattern=r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
    app_env: Literal["local", "test", "prod"]
    service_name: str = Field(pattern=r"^[a-z][a-z0-9-]*$")
    service_bind_host: str = "0.0.0.0"
    service_bind_port: int = Field(gt=0, le=65535)
    service_advertise_host: str
    service_advertise_port: int = Field(gt=0, le=65535)
    role: Literal["api", "worker"] = "api"
    service_scheme: Literal["http", "https"] = "http"
    metrics_bind_host: str = "127.0.0.1"
    metrics_port: int = Field(default=0, ge=0, le=65535)
    nacos_server_addr: str
    nacos_namespace: str
    nacos_runtime_user: str
    nacos_runtime_password_secret_file: Path
    nacos_log_dir: Path = Path(".local/logs/nacos")

    @field_validator("nacos_runtime_password_secret_file")
    @classmethod
    def readable_secret(cls, path: Path) -> Path:
        if not path.is_file() or not path.read_text(encoding="utf-8").strip():
            raise ValueError("Nacos 运行秘密文件缺失或为空")
        return path

    @model_validator(mode="after")
    def matching_namespace(self) -> Self:
        if self.metrics_port and self.metrics_port == self.service_bind_port:
            raise ValueError("指标端口必须独立于业务端口")
        if self.nacos_namespace != f"{self.project_code}-{self.app_env}":
            raise ValueError("namespace 与项目/环境不一致")
        ServiceInstance(
            self.service_advertise_host, self.service_advertise_port, self.service_scheme
        )
        try:
            address = ipaddress.ip_address(self.service_advertise_host)
        except ValueError:
            address = None
        if (
            self.service_advertise_host.lower() == "localhost"
            or address is not None
            and (address.is_loopback or address.is_unspecified)
        ):
            raise ValueError("注册地址必须是消费端可达地址，不能是 loopback 或 wildcard")
        if self.app_env != "local" and self.service_scheme != "https":
            raise ValueError("测试/生产服务必须声明 HTTPS 端点并配置信任链")
        return self

    @property
    def nacos_password(self) -> SecretStr:
        return SecretStr(
            self.nacos_runtime_password_secret_file.read_text(encoding="utf-8").strip()
        )

    @classmethod
    def from_environment(
        cls, expected_service: str, values: Mapping[str, str] | None = None
    ) -> Self:
        source = os.environ if values is None else values
        fields = cls.model_fields
        for key in source:
            if key.startswith(("SERVICE_", "NACOS_")) and key.lower() not in fields:
                raise ValueError(f"未声明或不应注入应用的环境字段: {key}")
        configured = cls.model_validate(
            {key.lower(): value for key, value in source.items() if key.lower() in fields}
        )
        if configured.service_name != expected_service:
            raise ValueError("SERVICE_NAME 必须匹配启动制品声明")
        return configured
