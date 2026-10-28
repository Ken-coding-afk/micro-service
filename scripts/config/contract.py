"""配置字段归属合同(设计 02·配置分类与唯一主要来源)。

publish.py、scripts/infra/init.py 与测试共用本模块:
- 环境绑定字段白名单(未知字段拒绝,不"最后读到谁就信谁");
- Nacos 配置源校验(禁止环境绑定/秘密字段进入 Nacos;服务级只允许覆盖
  共享配置中声明 allow_override 的运行参数);
- 资源声明的名称解析规则。
"""

from __future__ import annotations

import ipaddress
import re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

# ---------------------------------------------------------------------------
# 环境绑定字段注册表
# ---------------------------------------------------------------------------
# 分类:identity=项目标识 endpoint=组件地址 secret_ref=秘密引用 generated=本地生成(不提交)
ENV_FIELDS: dict[str, dict[str, str]] = {
    "PROJECT_CODE": {"category": "identity", "required": "yes"},
    "APP_ENV": {"category": "identity", "required": "yes"},
    "PUBLIC_ORIGIN": {"category": "endpoint", "required": "yes"},
    "IDENTITY_INTERNAL_ORIGIN": {"category": "endpoint", "required": "yes"},
    "IDENTITY_SIGNING_JWK_SECRET_FILE": {"category": "secret_ref", "required": "no"},
    "IDENTITY_BOOTSTRAP_SECRET_FILE": {"category": "secret_ref", "required": "no"},
    "GATEWAY_BFF_CLIENTS_SECRET_FILE": {"category": "secret_ref", "required": "no"},
    "RESOURCE_AUDIENCE": {"category": "binding", "required": "no"},
    "INTROSPECTION_CLIENT_ID": {"category": "binding", "required": "no"},
    "INTROSPECTION_CLIENT_SECRET_FILE": {"category": "secret_ref", "required": "no"},
    "GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE": {
        "category": "secret_ref",
        "required": "no",
    },
    "NACOS_SERVER_ADDR": {"category": "endpoint", "required": "yes"},
    "NACOS_CONSOLE_ADDR": {"category": "endpoint", "required": "yes"},
    "NACOS_NAMESPACE": {"category": "endpoint", "required": "yes"},
    "PG_HOST": {"category": "endpoint", "required": "yes"},
    "PG_PORT": {"category": "endpoint", "required": "yes"},
    "DB_NAME": {"category": "binding", "required": "no"},
    "DB_USER": {"category": "binding", "required": "no"},
    "DB_PASSWORD_SECRET_FILE": {"category": "secret_ref", "required": "no"},
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

# 管理凭证仅在对应操作中要求；external/verify 不要求交付集群超级用户。
for _key in (
    "POSTGRES_ADMIN_USER",
    "NACOS_ADMIN_USER",
    "RABBIT_ADMIN_USER",
    "NACOS_AUTH_IDENTITY_KEY",
):
    ENV_FIELDS[_key] = {"category": "binding", "required": "no"}
for _key in tuple(ENV_FIELDS):
    if ENV_FIELDS[_key]["category"] == "generated":
        ENV_FIELDS[_key]["required"] = "no"
        ENV_FIELDS[f"{_key}_SECRET_FILE"] = {"category": "secret_ref", "required": "no"}
for _key in (
    "PG_MODE",
    "REDIS_MODE",
    "NACOS_MODE",
    "RABBIT_MODE",
    "NACOS_PUBLISH_USER",
    "NACOS_RUNTIME_USER",
    "PG_CONTROL_DB",
    "PG_RESOURCE_PREFIX",
    "INFRA_PROJECT_NAME",
    "INFRA_NETWORK",
    "INFRA_BIND_HOST",
    "TRUSTED_PROXY_CIDRS",
    "COMPOSE_PROFILES",
    "MONITORING_TARGETS_FILE",
    "MONITORING_PROXY_CIDRS",
    "GRAFANA_INTERNAL_ORIGIN",
    "GRAFANA_BIND_HOST",
    "GRAFANA_PUBLISHED_PORT",
    "PROMETHEUS_IMAGE",
    "GRAFANA_IMAGE",
):
    ENV_FIELDS[_key] = {"category": "binding", "required": "no"}
for _key in ("NACOS_PUBLISH_PASSWORD", "NACOS_RUNTIME_PASSWORD"):
    ENV_FIELDS[_key] = {"category": "generated", "required": "no"}
    ENV_FIELDS[f"{_key}_SECRET_FILE"] = {"category": "secret_ref", "required": "no"}

PUBLISHED_PORTS = {
    "PG_PUBLISHED_PORT": 15432,
    "REDIS_PUBLISHED_PORT": 16379,
    "NACOS_PUBLISHED_PORT": 8848,
    "NACOS_GRPC_PUBLISHED_PORT": 9848,
    "NACOS_CONSOLE_PUBLISHED_PORT": 8080,
    "RABBIT_PUBLISHED_PORT": 5672,
    "RABBIT_MANAGEMENT_PUBLISHED_PORT": 15672,
    "GRAFANA_PUBLISHED_PORT": 13000,
}
for _key in PUBLISHED_PORTS:
    ENV_FIELDS[_key] = {"category": "endpoint", "required": "no"}

# 环境绑定字段不得出现在 Nacos 配置源中(同名覆盖一律拒绝)
NACOS_FORBIDDEN_KEYS = set(ENV_FIELDS) | {
    "SERVICE_NAME",
    "SERVICE_BIND_HOST",
    "SERVICE_BIND_PORT",
    "SERVICE_ADVERTISE_HOST",
    "SERVICE_ADVERTISE_PORT",
    "DB_NAME",
    "DB_USER",
    "DB_PASSWORD",
    "DB_PASSWORD_SECRET_FILE",
}

# 服务文件由项目选择；文件名只接受规范服务名，内容仍严格校验。
SHARED_DATA_ID = "application-shared.yaml"
SERVICE_DATA_ID = re.compile(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*\.yaml")

_NAME_RE = re.compile(r"^[a-z][a-z0-9_-]{0,62}$")

NACOS_FIELDS = {
    "allow_override",
    "log_level",
    "http.connect_timeout_seconds",
    "http.response_budget_seconds",
    "http.get_retry_extra",
    "observation.metrics_enabled",
    "project_display_name",
    "logo_url",
    "theme",
    "http.max_connections",
    "http.max_keepalive_connections",
}


@dataclass
class EnvFile:
    """已解析的环境绑定文件。"""

    values: dict[str, str] = field(default_factory=dict)


def parse_env_file(path: Path) -> EnvFile:
    """解析 KEY=VALUE 行;忽略空行与 # 注释;不展开变量。"""
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"环境文件第 {number} 行无法解析，不回显原始内容")
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if key in values:
            raise ValueError(f"环境文件存在重复字段: {key}")
        if key.endswith("_SECRET_FILE") and value and not Path(value).is_absolute():
            value = str((path.parent / value).resolve())
        values[key] = value
    return EnvFile(values)


def validate_env(env: EnvFile) -> list[str]:
    """校验环境绑定:未知字段拒绝、必填缺失提示、生成类占位未替换提示。"""
    issues: list[str] = []
    for key in env.values:
        if key not in ENV_FIELDS:
            issues.append(f"未知环境字段(拒绝): {key}")
    for key, meta in ENV_FIELDS.items():
        if meta["required"] == "yes" and not env.values.get(key):
            issues.append(f"缺少必填环境字段: {key}")
    values = env.values
    profiles = {p.strip() for p in values.get("COMPOSE_PROFILES", "").split(",") if p.strip()}
    if any(p != p.strip() for p in values.get("COMPOSE_PROFILES", "").split(",")):
        issues.append("COMPOSE_PROFILES 不能包含空白")
    if profiles - {"mq", "monitoring"}:
        issues.append("COMPOSE_PROFILES 只支持 mq,monitoring")
    if "monitoring" in profiles:
        for image_key in ("PROMETHEUS_IMAGE", "GRAFANA_IMAGE"):
            if not re.fullmatch(
                r"[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}", values.get(image_key, "")
            ):
                issues.append(image_key + " 必须指定核实后的镜像摘要")
        try:
            networks = [
                ipaddress.ip_network(v.strip(), strict=False)
                for v in values.get("MONITORING_PROXY_CIDRS", "").split(",")
                if v.strip()
            ]
            if not networks or any(
                n.prefixlen != n.max_prefixlen or not n.is_private for n in networks
            ):
                raise ValueError()
            address = ipaddress.ip_address(values.get("GRAFANA_BIND_HOST", "127.0.0.1"))
            if address.is_unspecified or not address.is_private:
                raise ValueError()
        except ValueError:
            issues.append(
                "监控须显式指定可信网关 MONITORING_PROXY_CIDRS，Grafana 只绑定 loopback/私网"
            )
    app_env = values.get("APP_ENV")
    if app_env not in ("local", "test", "prod"):
        issues.append("APP_ENV 必须为 local/test/prod")
    if values.get("INFRA_BIND_HOST"):
        try:
            address = ipaddress.ip_address(values["INFRA_BIND_HOST"])
            if address.is_unspecified or address.is_multicast or not address.is_private:
                raise ValueError("基础组件仅允许绑定明确内网地址")
        except ValueError:
            issues.append("INFRA_BIND_HOST 必须为明确内网或 loopback IP，不能绑定所有网卡")
    project = values.get("PROJECT_CODE", "")
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", project):
        issues.append("PROJECT_CODE 必须为小写字母数字及分隔连字符")
    if values.get("NACOS_NAMESPACE") != resolve_namespace(project, app_env or ""):
        issues.append("NACOS_NAMESPACE 与 PROJECT_CODE/APP_ENV 不一致")
    if values.get("PG_RESOURCE_PREFIX") and (
        app_env != "local"
        or values["PG_RESOURCE_PREFIX"]
        not in (project.replace("-", "_"), f"{project.replace('-', '_')}_local")
    ):
        issues.append("PG_RESOURCE_PREFIX 仅用于保留已核实的同项目 local 资源")
    proxies = values.get("TRUSTED_PROXY_CIDRS", "")
    if proxies.strip():
        for item in proxies.split(","):
            try:
                network = ipaddress.ip_network(item.strip(), strict=False)
                if network.prefixlen == 0:
                    raise ValueError("不能信任整个互联网")
            except ValueError:
                issues.append("TRUSTED_PROXY_CIDRS 必须登记明确的受限 IP/CIDR")
    for key in (
        "PG_PORT",
        "REDIS_PORT",
        "RABBIT_PORT",
        "RABBIT_MGMT_PORT",
        *PUBLISHED_PORTS,
    ):
        if key in values and (not values[key].isdigit() or not 1 <= int(values[key]) <= 65535):
            issues.append(f"端口超出合法范围: {key}")
    api_port = values.get("NACOS_PUBLISHED_PORT", "8848")
    grpc_port = values.get("NACOS_GRPC_PUBLISHED_PORT", "9848")
    if api_port.isdigit() and grpc_port.isdigit() and int(grpc_port) != int(api_port) + 1000:
        issues.append("Nacos 宿主 gRPC 端口必须等于 API 端口 + 1000")
    for key in ("PUBLIC_ORIGIN", "IDENTITY_INTERNAL_ORIGIN", "NACOS_CONSOLE_ADDR"):
        value = values.get(key, "")
        parsed = urlsplit(value)
        if (
            not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
        ):
            issues.append(f"来源地址不合法: {key}")
        elif parsed.scheme != "https" and not (
            app_env == "local"
            and parsed.scheme == "http"
            and (key != "PUBLIC_ORIGIN" or parsed.hostname in ("localhost", "127.0.0.1", "::1"))
        ):
            issues.append(f"来源地址必须使用受信任 HTTPS: {key}")
    for key in ("PG_MODE", "REDIS_MODE", "NACOS_MODE", "RABBIT_MODE"):
        mode = values.get(key, "self_hosted" if app_env == "local" else "")
        if mode not in ("self_hosted", "external"):
            issues.append(f"组件模式必须显式为 self_hosted/external: {key}")
    for key, value in values.items():
        if value.startswith("<") and value.endswith(">"):
            issues.append(f"生成类字段未替换占位符: {key}")
        if key.endswith("_SECRET_FILE") and value:
            path = Path(value)
            if not path.is_file() or not path.read_text(encoding="utf-8").strip():
                issues.append(f"秘密文件不存在或为空: {key}")
        if ENV_FIELDS.get(key, {}).get("category") == "generated" and value:
            if app_env != "local":
                issues.append(f"非 local 环境只接受秘密文件引用: {key}")
            if values.get(f"{key}_SECRET_FILE"):
                issues.append(f"秘密值与文件引用不能重复定义: {key}")
    return issues


def secret_value(values: dict[str, str], key: str) -> str:
    """解析单个操作所需秘密；错误只包含字段名，不输出秘密或文件正文。"""
    reference = values.get(f"{key}_SECRET_FILE")
    if reference:
        value = Path(reference).read_text(encoding="utf-8").strip()
    elif values.get("APP_ENV") == "local":
        value = values.get(key, "")
    else:
        value = ""
    if not value or value.startswith("<"):
        raise ValueError(f"缺少所需秘密: {key}_SECRET_FILE")
    return value


# ---------------------------------------------------------------------------
# Nacos 配置源校验
# ---------------------------------------------------------------------------


def _flatten(mapping: dict, prefix: str = "") -> set[str]:
    keys: set[str] = set()
    for key, value in mapping.items():
        full = f"{prefix}.{key}" if prefix else str(key)
        if isinstance(value, dict):
            keys |= _flatten(value, full)
        else:
            keys.add(full)
    return keys


def validate_nacos_source(source_dir: Path) -> list[str]:
    """校验 config/nacos 源目录。

    规则(设计 02):
    1. 只允许共享 Data ID + 规范服务名 Data ID;
    2. 任何文件不得包含环境绑定/秘密字段(NACOS_FORBIDDEN_KEYS);
    3. 服务级文件只能覆盖共享配置 allow_override 声明的键。
    """
    import yaml  # 延迟导入,保持纯文本校验可用

    issues: list[str] = []
    shared_path = source_dir / SHARED_DATA_ID
    if not shared_path.is_file():
        return [f"缺少必需共享配置: {SHARED_DATA_ID}"]
    try:
        shared = yaml.safe_load(shared_path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError:
        return ["共享配置 YAML 不合法"]
    if not isinstance(shared, dict) or not isinstance(shared.get("allow_override", []), list):
        return ["共享配置必须为 mapping，allow_override 必须为字段名数组"]
    shared_keys = _flatten(shared) - {"allow_override"}
    override_list = shared.get("allow_override", [])
    if not all(isinstance(key, str) for key in override_list):
        return ["allow_override 只能包含字段名字符串"]
    overridable = set(override_list)
    if not all(isinstance(key, str) and key in shared_keys for key in overridable):
        return ["allow_override 只能引用共享配置中已声明的字段"]
    for path in sorted(source_dir.glob("*.yaml")):
        if path.name == SHARED_DATA_ID:
            issues += _check_forbidden(shared, path.name)
        elif SERVICE_DATA_ID.fullmatch(path.name):
            try:
                doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            except yaml.YAMLError:
                issues.append(f"{path.name}: YAML 不合法")
                continue
            if not isinstance(doc, dict):
                issues.append(f"{path.name}: 配置必须为 mapping")
                continue
            issues += _check_forbidden(doc, path.name)
            for key in _flatten(doc):
                if key in shared_keys and key not in overridable:
                    issues.append(f"{path.name}: 覆盖了共享配置未声明 allow_override 的键 {key}")
        else:
            issues.append(f"不允许的 Data ID 文件: {path.name}")
    try:
        from .components import rule_source_issues
    except ImportError:
        from components import rule_source_issues
    return issues + rule_source_issues(source_dir)


def _check_forbidden(doc: dict, filename: str) -> list[str]:
    issues = []
    issues += _check_identity_brand(doc, filename)
    try:
        from .components import component_issues
    except ImportError:
        from components import component_issues
    issues += component_issues(doc, filename)
    if "business" in doc and (filename == SHARED_DATA_ID or not isinstance(doc["business"], dict)):
        issues.append(f"{filename}: business 必须是服务自己声明的 mapping")

    def business_keys(value, depth=0):
        if depth > 20:
            issues.append(f"{filename}: 业务配置嵌套过深")
            return
        if isinstance(value, dict):
            for name, child in value.items():
                if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9_]*", name):
                    issues.append(f"{filename}: 业务配置字段必须为小写标识符")
                    continue
                if name.upper() in NACOS_FORBIDDEN_KEYS or re.search(
                    r"(^|_)(password|secret|token|api_key|private_key)($|_)", name
                ):
                    issues.append(f"{filename}: 业务配置禁止秘密/环境绑定字段: {name}")
                business_keys(child, depth + 1)
        elif isinstance(value, list):
            for child in value:
                business_keys(child, depth + 1)

    business_keys(doc.get("business", {}))
    for key in _flatten(
        {k: v for k, v in doc.items() if k not in {"spring", "concurrency", "gateway"}}
    ):
        leaf = key.rsplit(".", 1)[-1].upper()
        full_upper = key.replace(".", "_").upper()
        if leaf in NACOS_FORBIDDEN_KEYS or full_upper in NACOS_FORBIDDEN_KEYS:
            issues.append(f"{filename}: 环境绑定/秘密字段禁止进入 Nacos: {key}")
        elif re.search(
            r"(^|_)(password|secret|token|api_key|private_key)($|_)",
            key.replace(".", "_").lower(),
        ):
            issues.append(f"{filename}: 秘密字段禁止进入 Nacos: {key}")
        elif (
            key not in NACOS_FIELDS
            and not re.fullmatch(
                r"http\.circuit_breakers\.[a-z][a-z0-9-]{0,62}\.(enabled|failure_threshold|recovery_timeout_seconds)",
                key,
            )
            and key != "business"
            and not key.startswith("business.")
        ):
            issues.append(f"{filename}: 未声明的运行参数字段: {key}")
    return issues


def _check_identity_brand(doc: dict, filename: str) -> list[str]:
    """品牌只属于身份服务；与 Java 启动快照使用相同的文本/同源路径边界。"""
    keys = {"project_display_name", "logo_url"} & doc.keys()
    if not keys:
        return []
    invalid = [f"{filename}: 身份品牌必须属于身份服务且为纯文本/同源路径"]
    if filename != "identity-service.yaml":
        return invalid
    name = doc.get("project_display_name", "")
    logo = doc.get("logo_url", "")
    if (
        not isinstance(name, str)
        or len(name) > 128
        or name
        and name.isspace()
        or any(ord(c) < 32 or 127 <= ord(c) <= 159 or 0xD800 <= ord(c) <= 0xDFFF for c in name)
        or "<" in name
        or ">" in name
        or not isinstance(logo, str)
    ):
        return invalid
    if logo:
        from urllib.parse import unquote

        if any(ord(c) <= 32 or 127 <= ord(c) <= 159 for c in logo):
            return invalid
        try:
            uri = urlsplit(logo)
            path = unquote(uri.path)
            if (
                len(logo) > 1024
                or not logo.startswith("/")
                or "?" in logo
                or "#" in logo
                or re.search(r"%(?![0-9A-Fa-f]{2})", logo)
                or uri.scheme
                or uri.netloc
                or uri.query
                or uri.fragment
                or not path.startswith("/")
                or path.startswith("//")
                or "\\" in path
                or any(
                    ord(c) < 32 or 127 <= ord(c) <= 159 or 0xD800 <= ord(c) <= 0xDFFF for c in path
                )
                or any(part in {".", ".."} for part in path.split("/"))
            ):
                return invalid
        except ValueError:
            return invalid
    return []


def resolve_pg_resource(resource: dict, values: dict[str, str]) -> dict[str, str]:
    """初始化与迁移共用同一物理资源解析；PG 标识符不允许静默截断。"""
    prefix = resolve_pg_prefix(values)
    result = {"database": f"{prefix}_{resource['name_binding']['database']}"}
    result.update({key: f"{prefix}_{value}" for key, value in resource["roles"].items()})
    for value in result.values():
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value):
            raise ValueError("资源名必须为最多 63 字节的小写 PG 标识符")
    return result


def resolve_pg_prefix(values: dict[str, str]) -> str:
    """默认含项目及环境，避免同一外部 PG 实例中的 test/prod 串用资源。"""
    return values.get("PG_RESOURCE_PREFIX") or resolve_prefix(
        resolve_namespace(values["PROJECT_CODE"], values["APP_ENV"])
    )


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
