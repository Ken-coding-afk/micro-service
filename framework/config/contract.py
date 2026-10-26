"""配置字段归属合同(设计 02·配置分类与唯一主要来源)。

publish.py、infra/init/run.py 与测试共用本模块:
- 环境绑定字段白名单(未知字段拒绝,不"最后读到谁就信谁");
- Nacos 配置源校验(禁止环境绑定/秘密字段进入 Nacos;服务级只允许覆盖
  共享配置中声明 allow_override 的运行参数);
- 资源声明的名称解析规则。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# 环境绑定字段注册表
# ---------------------------------------------------------------------------
# 分类:identity=项目标识 endpoint=组件地址 secret_ref=秘密引用 generated=本地生成(不提交)
ENV_FIELDS: dict[str, dict[str, str]] = {
    "PROJECT_CODE": {"category": "identity", "required": "yes"},
    "APP_ENV": {"category": "identity", "required": "yes"},
    "PUBLIC_ORIGIN": {"category": "endpoint", "required": "yes"},
    "IDENTITY_INTERNAL_ORIGIN": {"category": "endpoint", "required": "yes"},
    "NACOS_SERVER_ADDR": {"category": "endpoint", "required": "yes"},
    "NACOS_CONSOLE_ADDR": {"category": "endpoint", "required": "yes"},
    "NACOS_NAMESPACE": {"category": "endpoint", "required": "yes"},
    "PG_HOST": {"category": "endpoint", "required": "yes"},
    "PG_PORT": {"category": "endpoint", "required": "yes"},
    "REDIS_HOST": {"category": "endpoint", "required": "yes"},
    "REDIS_PORT": {"category": "endpoint", "required": "yes"},
    "REDIS_CONF_FILE": {"category": "secret_ref", "required": "no"},
    "RABBIT_HOST": {"category": "endpoint", "required": "no"},
    "RABBIT_PORT": {"category": "endpoint", "required": "no"},
    "RABBIT_MGMT_PORT": {"category": "endpoint", "required": "no"},
    "S3_ENDPOINT": {"category": "endpoint", "required": "no"},
    # 本地组件管理员凭证:仅 local 由 prepare-local 生成到 .local(不提交);
    # test/prod 由 secret 文件/密钥系统提供,值不进入 Git。
    "POSTGRES_ADMIN_USER": {"category": "generated", "required": "yes"},
    "POSTGRES_ADMIN_PASSWORD": {"category": "generated", "required": "yes"},
    "REDIS_PASSWORD": {"category": "generated", "required": "yes"},
    "NACOS_ADMIN_USER": {"category": "generated", "required": "yes"},
    "NACOS_ADMIN_PASSWORD": {"category": "generated", "required": "yes"},
    "NACOS_AUTH_TOKEN": {"category": "generated", "required": "yes"},
    "NACOS_AUTH_IDENTITY_KEY": {"category": "generated", "required": "yes"},
    "NACOS_AUTH_IDENTITY_VALUE": {"category": "generated", "required": "yes"},
    "RABBIT_ADMIN_USER": {"category": "generated", "required": "no"},
    "RABBIT_ADMIN_PASSWORD": {"category": "generated", "required": "no"},
}

# 环境绑定字段不得出现在 Nacos 配置源中(同名覆盖一律拒绝)
NACOS_FORBIDDEN_KEYS = set(ENV_FIELDS) | {
    "SERVICE_NAME", "SERVICE_BIND_HOST", "SERVICE_BIND_PORT",
    "SERVICE_ADVERTISE_HOST", "SERVICE_ADVERTISE_PORT",
    "DB_NAME", "DB_USER", "DB_PASSWORD", "DB_PASSWORD_SECRET_FILE",
}

# 允许提交到 Nacos 的 Data ID 白名单(设计 02:application-shared + 各服务)
SHARED_DATA_ID = "application-shared.yaml"
ALLOWED_SERVICE_DATA_IDS = ("identity-service.yaml", "api-gateway.yaml",
                            "document-service.yaml", "analysis-service.yaml",
                            "agent-service.yaml")

_NAME_RE = re.compile(r"^[a-z][a-z0-9_-]{0,62}$")


@dataclass
class EnvFile:
    """已解析的环境绑定文件。"""

    values: dict[str, str] = field(default_factory=dict)


def parse_env_file(path: Path) -> EnvFile:
    """解析 KEY=VALUE 行;忽略空行与 # 注释;不展开变量。"""
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"环境文件存在无法解析的行: {line!r}")
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return EnvFile(values)


def validate_env(env: EnvFile) -> list[str]:
    """校验环境绑定:未知字段拒绝、必填缺失提示、生成类占位未替换提示。"""
    issues: list[str] = []
    for key in env.values:
        if key not in ENV_FIELDS:
            issues.append(f"未知环境字段(拒绝): {key}")
    for key, meta in ENV_FIELDS.items():
        if meta["required"] == "yes" and key not in env.values:
            issues.append(f"缺少必填环境字段: {key}")
    for key in ("POSTGRES_ADMIN_PASSWORD", "REDIS_PASSWORD", "NACOS_ADMIN_PASSWORD"):
        value = env.values.get(key, "")
        if value.startswith("<") and value.endswith(">"):
            issues.append(f"生成类字段未替换占位符: {key}")
    return issues


# ---------------------------------------------------------------------------
# Nacos 配置源校验
# ---------------------------------------------------------------------------

def _flatten(mapping: dict, prefix: str = "") -> set[str]:
    keys: set[str] = set()
    for key, value in mapping.items():
        full = f"{prefix}.{key}" if prefix else str(key)
        keys.add(full)
        if isinstance(value, dict):
            keys |= _flatten(value, full)
    return keys


def validate_nacos_source(source_dir: Path) -> list[str]:
    """校验 config/nacos 源目录。

    规则(设计 02):
    1. 只允许共享 Data ID + 白名单服务 Data ID;
    2. 任何文件不得包含环境绑定/秘密字段(NACOS_FORBIDDEN_KEYS);
    3. 服务级文件只能覆盖共享配置 allow_override 声明的键。
    """
    import yaml  # 延迟导入,保持纯文本校验可用

    issues: list[str] = []
    shared_keys: set[str] = set()
    overridable: set[str] = set()
    for path in sorted(source_dir.glob("*.yaml")):
        if path.name == SHARED_DATA_ID:
            doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            shared_keys = _flatten(doc)
            overridable = {str(k) for k in doc.get("allow_override", [])}
            issues += _check_forbidden(doc, path.name)
        elif path.name in ALLOWED_SERVICE_DATA_IDS:
            doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            issues += _check_forbidden(doc, path.name)
            for key in _flatten(doc):
                if key in shared_keys and key not in overridable:
                    issues.append(
                        f"{path.name}: 覆盖了共享配置未声明 allow_override 的键 {key}"
                    )
        else:
            issues.append(f"不允许的 Data ID 文件: {path.name}")
    return issues


def _check_forbidden(doc: dict, filename: str) -> list[str]:
    issues = []
    for key in _flatten(doc):
        leaf = key.rsplit(".", 1)[-1].upper()
        full_upper = key.replace(".", "_").upper()
        if leaf in NACOS_FORBIDDEN_KEYS or full_upper in NACOS_FORBIDDEN_KEYS:
            issues.append(f"{filename}: 环境绑定/秘密字段禁止进入 Nacos: {key}")
    return issues


# ---------------------------------------------------------------------------
# 资源名称解析(设计 03·幂等)
# ---------------------------------------------------------------------------

def resolve_prefix(project_code: str) -> str:
    """PROJECT_CODE 转为 PG 标识符安全前缀(小写、- 转 _)。"""
    prefix = project_code.strip().lower().replace("-", "_")
    if not _NAME_RE.match(prefix):
        raise ValueError(f"PROJECT_CODE 含不允许字符: {project_code!r}")
    return prefix


def resolve_namespace(project_code: str, app_env: str) -> str:
    return f"{project_code}-{app_env}"
