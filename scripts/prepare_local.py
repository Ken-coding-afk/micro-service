"""仅为本地示例准备应用绑定；先执行 infra 初始化与迁移，再运行本入口。"""

import json
import secrets
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/config"))
import contract  # noqa: E402 - 固定框架工具目录，不从项目加载同名模块。


def create_once(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_text(encoding="utf-8") != content:
            raise ValueError(f"已有文件与预期不符，拒绝覆盖：{path.name}")
        return
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(content)
    path.chmod(0o600)


def random_file(path: Path, password=False) -> str:
    if path.exists():
        return path.read_text(encoding="utf-8").strip()
    value = ("Aa1!" if password else "") + secrets.token_urlsafe(32)
    create_once(path, value)
    return value


def env_file(path: Path, values: dict):
    if any("\n" in str(value) or "\r" in str(value) for value in values.values()):
        raise ValueError("环境值不得包含换行")
    create_once(path, "".join(f"{key}={value}\n" for key, value in values.items()))


def prepare(env_path: Path, images: dict, project_root: Path | None = None, initial_services=None):
    root = project_root.resolve() if project_root else ROOT
    env = contract.parse_env_file(env_path).values
    if env.get("APP_ENV") != "local":
        raise ValueError("示例准备入口仅允许 local")
    issues = contract.validate_env(contract.EnvFile(env))
    if issues:
        raise ValueError("环境绑定未通过校验")
    runtime = env_path.parent / "runtime"
    bootstrap = runtime / "identity-bootstrap-secrets"
    identity = runtime / "identity-service-secrets"
    gateway = runtime / "api-gateway-secrets"
    for folder in (bootstrap, identity, gateway):
        folder.mkdir(parents=True, exist_ok=True)
        folder.chmod(0o700)
    project = env["PROJECT_CODE"]
    prefix = contract.resolve_pg_prefix(env) + "_"
    common = {
        "PROJECT_CODE": project,
        "APP_ENV": "local",
        "PUBLIC_ORIGIN": env["PUBLIC_ORIGIN"],
        "SERVICE_BIND_HOST": "0.0.0.0",
        "SERVICE_SCHEME": "http",
        "NACOS_SERVER_ADDR": "nacos:8848"
        if env.get("NACOS_MODE") == "self_hosted"
        else env["NACOS_SERVER_ADDR"],
        "NACOS_NAMESPACE": env["NACOS_NAMESPACE"],
        "NACOS_RUNTIME_USER": env["NACOS_RUNTIME_USER"],
        "NACOS_RUNTIME_PASSWORD_SECRET_FILE": "/run/secrets/nacos-runtime.password",
    }
    database = {
        "PG_HOST": "postgres" if env.get("PG_MODE") == "self_hosted" else env["PG_HOST"],
        "PG_PORT": 5432 if env.get("PG_MODE") == "self_hosted" else env["PG_PORT"],
        "DB_NAME": prefix + "identity",
        "DB_USER": prefix + "identity_runtime",
        "DB_PASSWORD_SECRET_FILE": "/run/secrets/database.password",
    }
    db_secret = (env_path.parent / "secrets/identity-db-runtime.password").read_text(
        encoding="utf-8"
    )
    for folder in (identity, bootstrap):
        create_once(folder / "database.password", db_secret)
    for folder in (identity, gateway):
        create_once(
            folder / "nacos-runtime.password",
            contract.secret_value(env, "NACOS_RUNTIME_PASSWORD"),
        )
        create_once(folder / "redis.password", contract.secret_value(env, "REDIS_PASSWORD"))
    admin_password = random_file(bootstrap / "admin-initial.password", password=True)
    del admin_password  # 不打印；用户从受控文件读取。
    publishers = {}
    # 发行项目根 services；仓库内只读混合示例。权限所有者来自业务自己的清单。
    services = root / "services"
    owners = set()
    for manifest in sorted(services.rglob("permissions.yaml")):
        if {"target", ".venv", "node_modules", "__pycache__"}.intersection(manifest.parts):
            continue
        value = yaml.safe_load(manifest.read_text(encoding="utf-8"))
        service = value.get("service") if isinstance(value, dict) else None
        if not isinstance(service, str) or not contract.SERVICE_DATA_ID.fullmatch(
            service + ".yaml"
        ):
            raise ValueError("业务权限清单缺少合法 service")
        if initial_services is None or service in initial_services:
            owners.add(service)
    for service in sorted(owners):
        random_file(bootstrap / (service + ".password"))
        publishers[service] = "/run/secrets/" + service + ".password"
    for name in ("platform-bff.password", "tenant-bff.password"):
        value = random_file(bootstrap / name)
        create_once(gateway / name, value)
    create_once(
        bootstrap / "bootstrap.json",
        json.dumps(
            {
                "admin_username": "admin",
                "admin_display_name": "本地平台管理员",
                "initial_password_file": "/run/secrets/admin-initial.password",
                "platform_bff_secret_file": "/run/secrets/platform-bff.password",
                "tenant_bff_secret_file": "/run/secrets/tenant-bff.password",
                "publisher_secret_files": publishers,
            },
            ensure_ascii=False,
            indent=2,
        ),
    )
    create_once(
        gateway / "bff.json",
        json.dumps(
            {
                "platform_secret_file": "/run/secrets/platform-bff.password",
                "tenant_secret_file": "/run/secrets/tenant-bff.password",
            },
            indent=2,
        ),
    )
    env_file(
        runtime / "identity-bootstrap.env",
        {
            "PROJECT_CODE": project,
            "APP_ENV": "local",
            "PUBLIC_ORIGIN": env["PUBLIC_ORIGIN"],
            **database,
            "IDENTITY_BOOTSTRAP_SECRET_FILE": "/run/secrets/bootstrap.json",
        },
    )
    redis = {
        "REDIS_HOST": "redis" if env.get("REDIS_MODE") == "self_hosted" else env["REDIS_HOST"],
        "REDIS_PORT": 6379 if env.get("REDIS_MODE") == "self_hosted" else env["REDIS_PORT"],
        "REDIS_PASSWORD_SECRET_FILE": "/run/secrets/redis.password",
    }
    env_file(
        runtime / "identity-service.env",
        {
            **common,
            **database,
            **redis,
            "SERVICE_NAME": "identity-service",
            "SERVICE_BIND_PORT": 8081,
            "SERVICE_ADVERTISE_HOST": "identity-service",
            "SERVICE_ADVERTISE_PORT": 8081,
            "IDENTITY_SIGNING_JWK_SECRET_FILE": "/run/secrets/signing.json",
        },
    )
    env_file(
        runtime / "api-gateway.env",
        {
            **common,
            **redis,
            "SERVICE_NAME": "api-gateway",
            "SERVICE_BIND_PORT": 8080,
            "SERVICE_ADVERTISE_HOST": "api-gateway",
            "SERVICE_ADVERTISE_PORT": 8080,
            "GATEWAY_BFF_CLIENTS_SECRET_FILE": "/run/secrets/bff.json",
        },
    )
    if not (identity / "signing.json").exists():
        subprocess.run(
            [
                "docker",
                "run",
                "--rm",
                "-v",
                f"{identity.as_posix()}:/keys",
                "--entrypoint",
                "java",
                images["identity-service"],
                "-Dloader.main=com.ken.platform.identity.service.oauth.SigningKeyTool",
                "-cp",
                "/app/app.jar",
                "org.springframework.boot.loader.launch.PropertiesLauncher",
                "create",
                "/keys/signing.json",
            ],
            check=True,
        )
    origin = urlsplit(env["PUBLIC_ORIGIN"])
    env_file(
        runtime / "compose.env",
        {
            "PROJECT_CODE": project,
            "APP_ENV": "local",
            "RUNTIME_DIR": runtime.as_posix(),
            "INFRA_NETWORK": env.get("INFRA_NETWORK", project + "-local-net"),
            "IDENTITY_IMAGE": images["identity-service"],
            "GATEWAY_IMAGE": images["api-gateway"],
            "NGINX_IMAGE": images["nginx"],
            "ENTRY_PORT": origin.port or 80,
            "IDENTITY_ADMIN_PORT": (origin.port or 80) + 1,
            "WEB_ROOT": (root / "web").as_posix(),
        },
    )
    return runtime
