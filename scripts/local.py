"""仅编排本项目的 local 依赖、首次身份安装及宿主运行绑定。"""

from __future__ import annotations

import hashlib
import json
import os
import socket
import subprocess
import sys
from pathlib import Path

import httpx
from build import base_images, build_service, business_compose
from catalog import database_resource, project_config
from common import FRAMEWORK, load_tool, read_yaml, run, service_directory, write


def env_values(path: Path) -> dict:
    return dict(
        line.split("=", 1)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#") and "=" in line
    )


def generated(root: Path, path: Path, content: str) -> None:
    state = root / ".local/generated-files.json"
    hashes = json.loads(state.read_text(encoding="utf-8")) if state.exists() else {}
    key = path.relative_to(root).as_posix()
    if path.exists():
        old = path.read_bytes()
        if old == content.encode():
            return
        if hashes.get(key) != hashlib.sha256(old).hexdigest():
            raise ValueError(f"派生文件已人工修改，拒绝覆盖：{key}")
    write(path, content)
    hashes[key] = hashlib.sha256(path.read_bytes()).hexdigest()
    write(state, json.dumps(hashes, indent=2))


def generated_env(root: Path, path: Path, values: dict) -> None:
    if any("\n" in str(v) or "\r" in str(v) for v in values.values()):
        raise ValueError("环境值不能含换行")
    generated(root, path, "".join(f"{k}={v}\n" for k, v in values.items()))


def tool(root: Path, path: str, *args: str | Path) -> None:
    run([sys.executable, FRAMEWORK / path, *args], cwd=root)


def compose(root: Path, *args: str) -> None:
    run(
        [
            "docker",
            "compose",
            "--project-directory",
            root,
            "--env-file",
            root / ".local/runtime/compose.env",
            "-f",
            root / "deploy/compose.yaml",
            "-f",
            root / ".local/runtime/host.compose.yaml",
            *args,
        ],
        cwd=root,
    )


def permission_file(root: Path, record: dict) -> Path:
    service = service_directory(root, record)
    return service / (
        "src/main/resources/permissions.yaml"
        if record["language"] == "java"
        else "src/" + record["package"] + "/resources/permissions.yaml"
    )


def prepare_environment(root: Path, config: dict, dev_host: str | None) -> tuple[Path, str]:
    local = root / ".local"
    env_path = local / "environment.env"
    offset = config["local"]["port_offset"]
    hostname = dev_host
    binding = local / "dev-host.txt"
    if binding.exists():
        existing = binding.read_text(encoding="utf-8").strip()
        if hostname and hostname != existing:
            raise ValueError("DEV_HOST 已固定，变更时先审核运行绑定；不静默改址")
        hostname = existing
    elif not hostname:
        hostname = "host.docker.internal" if os.name == "nt" else None
    if not hostname or not re_host(hostname):
        raise ValueError("请指定 --dev-host <容器和宿主可达的本机私网IP/名称>")
    try:
        socket.getaddrinfo(hostname, None)
    except socket.gaierror as error:
        raise ValueError(
            f"宿主不能解析 {hostname}；用 --dev-host <本机私网IP> 明确双方可达地址，不自动修改 hosts"
        ) from error
    if not env_path.exists():
        ports = [15432, 16379, 8848, 9848, 8080, 18080, 18081, 48082, 48083]
        for port in ports:
            try:
                with socket.socket() as probe:
                    probe.bind(("127.0.0.1", port + offset))
            except OSError as error:
                raise ValueError(
                    f"本地端口 {port + offset} 已占用或不可绑定；初始化前调整 development.yaml 的 local.port_offset 和服务 port，或创建时选择另一 --port-offset"
                ) from error
        values = env_values(root / "config/environments/local.env.example")
        values["PROJECT_CODE"] = config["local"]["project"]
        values["PUBLIC_ORIGIN"] = f"http://localhost:{18080 + offset}"
        values["NACOS_SERVER_ADDR"] = f"127.0.0.1:{8848 + offset}"
        values["NACOS_CONSOLE_ADDR"] = f"http://127.0.0.1:{8080 + offset}"
        for key, port in {
            "PG_PORT": 15432,
            "PG_PUBLISHED_PORT": 15432,
            "REDIS_PORT": 16379,
            "REDIS_PUBLISHED_PORT": 16379,
            "NACOS_PUBLISHED_PORT": 8848,
            "NACOS_GRPC_PUBLISHED_PORT": 9848,
            "NACOS_CONSOLE_PUBLISHED_PORT": 8080,
        }.items():
            values[key] = port + offset
        for kind in ("PUBLISH", "RUNTIME"):
            values[f"NACOS_{kind}_USER"] = (
                config["local"]["project"]
                + "-local-"
                + ("publisher" if kind == "PUBLISH" else "runtime")
            )
        write(env_path, "".join(f"{k}={v}\n" for k, v in values.items()))
        env_path.chmod(0o600)
        write(binding, hostname)
    values = env_values(env_path)
    if values.get("PROJECT_CODE") != config["local"]["project"] or values.get("APP_ENV") != "local":
        raise ValueError("仅允许当前项目默认命名的 local 环境")
    return env_path, hostname


def re_host(value: str) -> bool:
    import re

    return bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{0,252}", value))


def migrate(
    root: Path,
    env_path: Path,
    resources: Path,
    target: str,
    language: str,
    migrations: Path,
    image: str | None = None,
    *,
    resources_prepared: bool = False,
) -> None:
    if not resources_prepared:
        tool(
            root,
            "scripts/infra/init.py",
            "--env-file",
            env_path,
            "--phase",
            "resources",
            "--resources",
            resources,
        )
    tool(
        root,
        "scripts/deploy/migrate.py",
        "--env-file",
        env_path,
        "--resources",
        resources,
        "--target",
        target,
        "--tool",
        "flyway" if language == "java" else "alembic",
        "--migrations-dir",
        migrations,
        "--yes",
        *(["--migration-image", image] if image else []),
    )


def own_postgres(root: Path, env: dict) -> None:
    ids = subprocess.check_output(
        [
            "docker",
            "ps",
            "--filter",
            f"label=com.docker.compose.project={env['PROJECT_CODE']}-local-infra",
            "--filter",
            "label=com.docker.compose.service=postgres",
            "--format",
            "{{.ID}}",
        ],
        text=True,
    ).split()
    if len(ids) != 1:
        raise ValueError("未找到唯一的本项目 PostgreSQL 容器")
    facts = json.loads(subprocess.check_output(["docker", "inspect", ids[0]], text=True))[0]
    volumes = [v for v in facts["Mounts"] if v["Type"] == "volume"]
    if not any(v["Name"] == env["PROJECT_CODE"] + "-local-infra_pgdata" for v in volumes):
        raise ValueError("PostgreSQL 卷不是本项目 local 卷")


def bind_business(root: Path, config: dict, env: dict, host: str, with_demo: bool = False) -> None:
    prepare = load_tool("scripts/prepare_local.py")
    runtime = root / ".local/runtime"
    bootstrap = runtime / "identity-bootstrap-secrets"
    gateway = runtime / "api-gateway-secrets"
    manifest_path = bootstrap / "local.json"
    provisioning = bootstrap / "services.json"
    existing_names = {p.stem for p in runtime.glob("*.env")}

    def secret(path: Path, password=False, existing=False):
        if existing and not path.exists():
            raise ValueError("首次秘密丢失，必须显式轮换：" + path.name)
        return prepare.random_file(path, password=password)

    manifests = []
    reader_path = gateway / "readers.json"
    reader_map = (
        {v["audience"]: v for v in json.loads(reader_path.read_text(encoding="utf-8"))["readers"]}
        if reader_path.exists()
        else {}
    )
    scopes = {"profile"}
    for name in config["services"]:
        record = config["services"][name]
        folder = runtime / (name + "-secrets")
        for kind in ("resource", "gateway"):
            value = secret(bootstrap / f"{name}-{kind}.password", existing=name in existing_names)
            prepare.create_once(
                (folder / "introspection.password")
                if kind == "resource"
                else gateway / f"{name}-reader.password",
                value,
            )
        secret(bootstrap / f"{name}.password", existing=name in existing_names)
        resource = database_resource(root, record)
        resolved = load_tool("scripts/config/contract.py").resolve_pg_resource(resource, env)
        prepare.create_once(
            folder / "database.password",
            (root / f".local/secrets/{resource['resource_id']}-runtime.password").read_text(
                encoding="utf-8"
            ),
        )
        prepare.create_once(
            folder / "nacos-runtime.password",
            load_tool("scripts/config/contract.py").secret_value(env, "NACOS_RUNTIME_PASSWORD"),
        )
        manifests.append(
            {
                "name": name,
                "publisher_secret_file": f"/run/secrets/{name}.password",
                "swagger_redirect": f"http://localhost:{record['port']}"
                + (
                    "/swagger-ui/oauth2-redirect.html"
                    if record["language"] == "java"
                    else "/docs/oauth2-redirect"
                ),
                "resource_secret_file": f"/run/secrets/{name}-resource.password",
                "gateway_secret_file": f"/run/secrets/{name}-gateway.password",
                "permissions_sha256": hashlib.sha256(
                    permission_file(root, record).read_bytes()
                ).hexdigest(),
            }
        )
        reader_map[name] = {
            "audience": name,
            "client_id": name + "-gateway",
            "secret_file": f"/run/secrets/{name}-reader.password",
        }
        scopes.update(
            p["oauth_scope"] for p in read_yaml(permission_file(root, record))["permissions"]
        )
        values = {
            "PROJECT_CODE": env["PROJECT_CODE"],
            "APP_ENV": "local",
            "PUBLIC_ORIGIN": env["PUBLIC_ORIGIN"],
            "SERVICE_NAME": name,
            "SERVICE_BIND_HOST": "0.0.0.0",
            "SERVICE_BIND_PORT": record["port"],
            "SERVICE_ADVERTISE_HOST": name,
            "SERVICE_ADVERTISE_PORT": record["port"],
            "SERVICE_SCHEME": "http",
            "NACOS_SERVER_ADDR": "nacos:8848"
            if env.get("NACOS_MODE") == "self_hosted"
            else env["NACOS_SERVER_ADDR"],
            "NACOS_NAMESPACE": env["NACOS_NAMESPACE"],
            "NACOS_RUNTIME_USER": env["NACOS_RUNTIME_USER"],
            "NACOS_RUNTIME_PASSWORD_SECRET_FILE": "/run/secrets/nacos-runtime.password",
            "PG_HOST": "postgres" if env.get("PG_MODE") == "self_hosted" else env["PG_HOST"],
            "PG_PORT": 5432 if env.get("PG_MODE") == "self_hosted" else env["PG_PORT"],
            "DB_NAME": resolved["database"],
            "DB_USER": resolved["runtime"],
            "DB_PASSWORD_SECRET_FILE": "/run/secrets/database.password",
            "RESOURCE_AUDIENCE": name,
            "INTROSPECTION_CLIENT_ID": name + "-resource",
            "INTROSPECTION_CLIENT_SECRET_FILE": "/run/secrets/introspection.password",
            "PEER_SERVICE": record["peer"] or "",
        }
        generated_env(root, runtime / (name + ".env"), values)
        host_values = {
            k: str(v)
            for k, v in values.items()
            if k
            in {
                "PG_HOST",
                "PG_PORT",
                "NACOS_SERVER_ADDR",
                "SERVICE_BIND_HOST",
                "SERVICE_BIND_PORT",
                "SERVICE_ADVERTISE_HOST",
                "SERVICE_ADVERTISE_PORT",
            }
            or k.endswith("_SECRET_FILE")
        }
        host_values |= {
            "PG_HOST": env["PG_HOST"],
            "PG_PORT": env["PG_PORT"],
            "NACOS_SERVER_ADDR": env["NACOS_SERVER_ADDR"],
            "SERVICE_ADVERTISE_HOST": host,
        }
        host_values = {
            k: (str(folder / Path(v).name) if k.endswith("_SECRET_FILE") else v)
            for k, v in host_values.items()
        }
        generated_env(root, runtime / (name + "-host.env"), host_values)
    web_secret = secret(bootstrap / "web.password", existing=(gateway / "web.password").exists())
    prepare.create_once(gateway / "web.password", web_secret)
    generated(
        root,
        provisioning,
        json.dumps(
            {
                "services": manifests,
                "web_secret_file": "/run/secrets/web.password",
                "demo": with_demo,
            },
            indent=2,
        ),
    )
    if with_demo:
        password_files = {}
        for tenant in ("alpha", "beta"):
            for user in ("admin", "writer", "denied"):
                filename = f"{tenant}-{user}.password"
                secret(bootstrap / filename, password=True, existing=manifest_path.exists())
                password_files[tenant + ":" + user] = "/run/secrets/" + filename
        prepare.create_once(
            manifest_path,
            json.dumps(
                {
                    "services": manifests,
                    "password_files": password_files,
                    "web_secret_file": "/run/secrets/web.password",
                },
                indent=2,
            ),
        )
    generated(root, reader_path, json.dumps({"readers": list(reader_map.values())}, indent=2))
    bff = json.loads((gateway / "bff.json").read_text(encoding="utf-8"))
    scopes.update(bff.get("web", {}).get("requested_scopes", []))
    bff["web"] = {"secret_file": "/run/secrets/web.password", "requested_scopes": sorted(scopes)}
    generated(root, gateway / "bff.json", json.dumps(bff, indent=2))
    values = env_values(runtime / "api-gateway.env")
    values["GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE"] = "/run/secrets/readers.json"
    generated_env(root, runtime / "api-gateway.env", values)
    import yaml

    generated(
        root,
        runtime / "business.compose.yaml",
        yaml.safe_dump(business_compose(config), sort_keys=False),
    )


def initialize(root: Path, args) -> None:
    config = project_config(root, getattr(args, "services", "all"))
    with_demo = getattr(args, "with_demo", False)
    issues = load_tool("scripts/config/contract.py").validate_nacos_source(root / "config/nacos")
    if issues:
        raise ValueError("项目配置未通过校验：" + "; ".join(issues))
    env_path, host = prepare_environment(root, config, args.dev_host)
    initial_env = env_values(env_path)
    if with_demo and (
        initial_env.get("PG_MODE") != "self_hosted" or initial_env.get("PG_RESOURCE_PREFIX")
    ):
        raise ValueError("--with-demo 只允许本项目默认命名的自建 local PG")
    for name in config["services"]:
        record = config["services"][name]
        if record["language"] == "python":
            run(["uv", "sync", "--frozen", "--project", service_directory(root, record)], cwd=root)
    print("[init] 构建基础镜像", flush=True)
    images = base_images(root)
    image_state = root / ".local/migration-images.json"
    migration_images = (
        json.loads(image_state.read_text(encoding="utf-8")) if image_state.exists() else {}
    )
    for name, record in config["services"].items():
        if record["language"] == "python":
            migration_images[name] = build_service(
                root,
                name,
                record,
                f"local/{config['local']['project']}-{name}-migration:development",
                {"project_commit": "local-development", "dirty": True},
                migration=True,
            )
    write(root / ".local/migration-images.json", json.dumps(migration_images, indent=2))
    tool(
        root,
        "scripts/infra/init.py",
        "--env-file",
        env_path,
        "--phase",
        "prepare-local",
        "--redis-template",
        root / "infra/redis/redis.conf",
    )
    tool(
        root,
        "scripts/infra/up.py",
        "--env-file",
        env_path,
        "--compose-file",
        root / "infra/compose.yaml",
    )
    env = env_values(env_path)
    if with_demo:
        own_postgres(root, env)
    print("[init] 初始化资源和迁移", flush=True)
    for declaration in [
        root / "infra/resources.yaml",
        *[root / record["resources"] for record in config["services"].values()],
    ]:
        tool(
            root,
            "scripts/infra/init.py",
            "--env-file",
            env_path,
            "--phase",
            "resources",
            "--resources",
            declaration,
        )
    from catalog import nacos_files

    tool(
        root,
        "scripts/config/publish.py",
        "--env-file",
        env_path,
        "--source",
        root / "config/nacos",
        *[
            arg
            for path in nacos_files(root, config["selected"])
            for arg in ("--data-id", path.name)
        ],
    )
    migrate(
        root,
        env_path,
        root / "infra/resources.yaml",
        "identity-db",
        "java",
        root / "services/identity-service/src/main/resources/db/migration",
        resources_prepared=True,
    )
    runtime = root / ".local/runtime"
    if not (runtime / "compose.env").exists():
        load_tool("scripts/prepare_local.py").prepare(env_path, images, root, [])
        hashes = {
            p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in [
                runtime / "compose.env",
                runtime / "api-gateway.env",
                runtime / "api-gateway-secrets/bff.json",
            ]
        }
        write(root / ".local/generated-files.json", json.dumps(hashes, indent=2))
    values = env_values(runtime / "compose.env")
    values |= {
        "IDENTITY_IMAGE": images["identity-service"],
        "GATEWAY_IMAGE": images["api-gateway"],
        "EDGE_CONFIG_FILE": (root / "deploy/nginx/local.conf").as_posix(),
    }
    generated_env(root, runtime / "compose.env", values)
    port = int(values["IDENTITY_ADMIN_PORT"])
    override = {
        "services": {
            "identity-service": {
                "ports": [f"0.0.0.0:{port}:8081"],
                "extra_hosts": [f"{host}:host-gateway"],
                "environment": {"SERVICE_ADVERTISE_HOST": host, "SERVICE_ADVERTISE_PORT": port},
            },
            "api-gateway": {"extra_hosts": [f"{host}:host-gateway"]},
        }
    }
    import yaml

    generated(root, runtime / "host.compose.yaml", yaml.safe_dump(override, sort_keys=False))
    print("[init] 身份管理端口仅用于本地联调，监听宿主网卡；请用防火墙限制开发来源。", flush=True)
    compose(root, "run", "--rm", "identity-bootstrap")
    bind_business(root, config, env, host, with_demo)
    compose(
        root,
        "run",
        "--rm",
        "-e",
        "SERVICE_PROVISIONING_SECRET_FILE=/run/secrets/services.json",
        "identity-bootstrap",
        "bootstrap-services-technical",
    )
    compose(root, "up", "-d", "--wait", "identity-service")
    publisher = load_tool("scripts/deploy/sync_permissions.py")
    with httpx.Client(timeout=30, trust_env=False) as client:
        for name in config["services"]:
            publisher.publish(
                client,
                f"http://localhost:{port}",
                "catalog-publisher-" + hashlib.sha256(name.encode()).hexdigest()[:24],
                (runtime / f"identity-bootstrap-secrets/{name}.password")
                .read_text(encoding="utf-8")
                .strip(),
                permission_file(root, config["services"][name]),
                "local-initial",
            )
    if with_demo:
        compose(
            root,
            "run",
            "--rm",
            "-e",
            "LOCAL_PG_MODE=self_hosted",
            "-e",
            "LOCAL_BOOTSTRAP_SECRET_FILE=/run/secrets/local.json",
            "identity-bootstrap",
            "bootstrap-local",
        )
    compose(
        root,
        "run",
        "--rm",
        "-e",
        "SERVICE_PROVISIONING_SECRET_FILE=/run/secrets/services.json",
        "identity-bootstrap",
        "bootstrap-services-applications",
    )
    for name in config["services"]:
        record = config["services"][name]
        service = service_directory(root, record)
        migrate(
            root,
            env_path,
            service / "resources.yaml",
            database_resource(root, record)["resource_id"],
            record["language"],
            service
            / ("src/main/resources/db/migration" if record["language"] == "java" else "migrations"),
            migration_images.get(name),
            resources_prepared=True,
        )
    compose(root, "up", "-d", "--wait", "api-gateway", "nginx")
    compose(
        root,
        "exec",
        "-T",
        "api-gateway",
        "curl",
        "-fsS",
        f"http://{host}:{port}/actuator/health/readiness",
    )
    compose(root, "exec", "-T", "nginx", "nginx", "-t")
    compose(root, "exec", "-T", "nginx", "nginx", "-s", "reload")
    print(
        f"基础运行环境已就绪：{env['PUBLIC_ORIGIN']}；业务尚未启动。首次密码见 .local/runtime/identity-bootstrap-secrets。"
    )
    for name, record in config["services"].items():
        print("下一终端：uv run --frozen python scripts/dev.py " + name)
        docs = "/swagger-ui/index.html" if record["language"] == "java" else "/docs"
        print(
            f"Swagger（服务启动后）：http://localhost:{record['port']}{docs}；授权时指定 --tenant-code。"
        )
