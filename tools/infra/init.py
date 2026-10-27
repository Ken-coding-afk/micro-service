"""tools/infra/init.py — 环境初始化固定入口(设计 03·数据库、账号及命名空间初始化)。

两个阶段,职责分离(设计 03·幂等):
  prepare-local  生成不存在的本地秘密与环境文件(.local/,不覆盖已存在内容),
                 创建项目 external network,渲染 Redis 本地配置副本。
  resources      按资源声明幂等创建/核对:每服务 PG 库与 NOLOGIN owner +
                 migrator/runtime 角色、Nacos namespace、Rabbit vhost/账号。

不变量:
- 重复执行不改密码、不清库、不吞不一致;已有资源先核对归属,冲突即停止该资源;
- 资源状态记录 ABSENT/CREATED/VERIFIED/CONFLICT/FAILED,结果不记录密码;
- 部分失败保留已成功资源,不自动删除回滚;
- 本地文件锁避免并发初始化同一环境。
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path

import httpx
import psycopg
import yaml
from psycopg import sql

FRAMEWORK_ROOT = Path(__file__).resolve().parents[2]
INPUT_ROOT = FRAMEWORK_ROOT / "template" if (FRAMEWORK_ROOT / "template").is_dir() else FRAMEWORK_ROOT
sys.path.insert(0, str(FRAMEWORK_ROOT / "tools/config"))
import contract  # noqa: E402

STATE_ABSENT = "ABSENT"
RESOURCE_STATES = ("ABSENT", "CREATED", "VERIFIED", "CONFLICT", "FAILED")


def load_env(path: Path) -> dict[str, str]:
    env = contract.parse_env_file(path)
    issues = contract.validate_env(env)
    if issues:
        for issue in issues:
            print(f"[env] {issue}", file=sys.stderr)
        raise SystemExit("环境绑定文件校验失败,拒绝执行")
    return env.values


# ---------------------------------------------------------------------------
# prepare-local
# ---------------------------------------------------------------------------

GENERATED_FIELDS = {
    "POSTGRES_ADMIN_PASSWORD": lambda: secrets.token_urlsafe(24),
    "REDIS_PASSWORD": lambda: secrets.token_urlsafe(24),
    "NACOS_ADMIN_PASSWORD": lambda: secrets.token_urlsafe(24),
    # Nacos 3.2 要求 Base64 且 >= 32 字节
    "NACOS_AUTH_TOKEN": lambda: base64.b64encode(secrets.token_bytes(48)).decode(),
    "NACOS_AUTH_IDENTITY_VALUE": lambda: secrets.token_urlsafe(16),
    "RABBIT_ADMIN_PASSWORD": lambda: secrets.token_urlsafe(24),
    "NACOS_PUBLISH_PASSWORD": lambda: secrets.token_urlsafe(24),
    "NACOS_RUNTIME_PASSWORD": lambda: secrets.token_urlsafe(24),
}


def _prepare_local_files(args: argparse.Namespace) -> int:
    example = INPUT_ROOT / "config" / "environments" / "local.env.example"
    target = Path(args.env_file).resolve()
    local_dir = target.parent
    local_dir.mkdir(parents=True, exist_ok=True)
    source = target if target.exists() else example
    existing = contract.parse_env_file(source).values
    if existing.get("APP_ENV") != "local":
        raise ValueError("prepare-local 只允许 APP_ENV=local")
    redis_template = Path(
        getattr(args, "redis_template", None) or INPUT_ROOT / "infra/redis/redis.conf"
    )
    template = redis_template.read_text(encoding="utf-8")
    lines = []
    for raw in source.read_text(encoding="utf-8").splitlines():
        key, sep, value = raw.partition("=")
        if not sep:
            lines.append(raw)
            continue
        key, value = key.strip(), value.strip()
        if key in GENERATED_FIELDS and value.startswith("<"):
            value = GENERATED_FIELDS[key]()
        if key == "NACOS_NAMESPACE" and value.startswith("<"):
            # namespace 绑定 = {PROJECT_CODE}-{APP_ENV}
            code = next(
                (
                    l.partition("=")[2].strip()
                    for l in lines
                    if l.startswith("PROJECT_CODE=")
                ),
                "",
            )
            app_env = next(
                (
                    l.partition("=")[2].strip()
                    for l in lines
                    if l.startswith("APP_ENV=")
                ),
                "",
            )
            value = contract.resolve_namespace(code, app_env)
        lines.append(f"{key}={value}")
    for key in ("NACOS_PUBLISH_USER", "NACOS_RUNTIME_USER"):
        if key not in existing:
            suffix = "publisher" if key == "NACOS_PUBLISH_USER" else "runtime"
            lines.append(f"{key}={existing['PROJECT_CODE']}-local-{suffix}")
    for key in ("NACOS_PUBLISH_PASSWORD", "NACOS_RUNTIME_PASSWORD"):
        if key not in existing:
            lines.append(f"{key}={GENERATED_FIELDS[key]()}")
    lines = [
        f"REDIS_CONF_FILE={(local_dir / 'redis' / 'redis.conf').as_posix()}"
        if line.startswith("REDIS_CONF_FILE=")
        else line
        for line in lines
    ]
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.chmod(target, 0o600)
    print(f"[prepare-local] 已核对 {target}(已有秘密不变，样例占位符已补齐)")

    env = load_env(target)

    # 渲染 Redis 本地配置副本(仓库模板中的占位符替换为真实密码)
    redis_dir = local_dir / "redis"
    redis_dir.mkdir(exist_ok=True)
    rendered = template.replace("__REDIS_PASSWORD__", env["REDIS_PASSWORD"])
    redis_conf = redis_dir / "redis.conf"
    if not redis_conf.exists():
        redis_conf.write_text(rendered, encoding="utf-8")
        print(f"[prepare-local] 已生成 {redis_conf}")

    # 显式创建项目 external network(infra 与 deploy 两个 Compose 项目共用)
    network = env.get("INFRA_NETWORK") or f"{env['PROJECT_CODE']}-{env['APP_ENV']}-net"
    result = subprocess.run(
        ["docker", "network", "inspect", network],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        subprocess.run(["docker", "network", "create", network], check=True)
        print(f"[prepare-local] 已创建网络 {network}")
    else:
        print(f"[prepare-local] 网络 {network} 已存在")
    return 0


def phase_prepare_local(args: argparse.Namespace) -> int:
    target = Path(args.env_file).resolve()
    with LocalLock(target.parent / "init.lock"):
        return _prepare_local_files(args)


# ---------------------------------------------------------------------------
# resources
# ---------------------------------------------------------------------------


class LocalLock:
    """进程级文件锁；进程退出由 OS 释放，不靠删除 PID 文件抢占陈旧锁。"""

    def __init__(self, path: Path):
        self.path = path

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.handle = self.path.open("a+b")
        self.handle.seek(0, 2)
        if self.handle.tell() == 0:
            self.handle.write(b"\0")
            self.handle.flush()
        self.handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(self.handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(self.handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return self
        except OSError as exc:
            self.handle.close()
            raise ValueError("另一初始化进程正在运行，拒绝并发执行") from exc

    def __exit__(self, *exc):
        self.handle.seek(0)
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(self.handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.flock(self.handle.fileno(), fcntl.LOCK_UN)
        self.handle.close()


def _connect_pg(env: dict[str, str]):
    return psycopg.connect(
        host=env["PG_HOST"],
        port=int(env["PG_PORT"]),
        user=env["POSTGRES_ADMIN_USER"],
        password=contract.secret_value(env, "POSTGRES_ADMIN_PASSWORD"),
        dbname=env.get("PG_CONTROL_DB", "postgres"),
        autocommit=True,
    )


def _nacos_login(
    env: dict[str, str], bootstrap: bool = False
) -> tuple[httpx.Client, str]:
    """经 console 端口登录获取 accessToken;首次启动时初始化 admin。

    实施发现(T-01/T-02,Nacos 3.2.4):console(8080)与引擎(8848)分离,
    namespace 管理走 console `/v3/console/core/namespace*`,鉴权用
    `accessToken` 请求头;首次无用户时经 `POST /v3/auth/user/admin` 初始化。
    """
    console = env["NACOS_CONSOLE_ADDR"].rstrip("/")
    client = httpx.Client(timeout=10)
    marker = Path(env["_ENV_DIR"]) / "nacos-admin-initialized"

    def login() -> str | None:
        resp = client.post(
            f"{console}/v3/auth/user/login",
            data={
                "username": env["NACOS_ADMIN_USER"],
                "password": contract.secret_value(env, "NACOS_ADMIN_PASSWORD"),
            },
        )
        if resp.status_code == 200:
            # 实测:该端点成功响应为 {"accessToken":...,"tokenTtl":...},无 code 字段
            return resp.json().get("accessToken")
        return None

    token = _retry(lambda: login(), waits=(1, 3, 5))
    if token:
        return client, token

    # 登录失败且从未初始化过 → 首次 admin 初始化(console 端口,Nacos 3.x 合同)
    if marker.exists() or not bootstrap:
        raise SystemExit(
            "Nacos admin 登录失败但已标记初始化过;不重置密码,请人工核对凭证"
        )
    resp = client.post(
        f"{console}/v3/auth/user/admin",
        data={
            "username": env["NACOS_ADMIN_USER"],
            "password": contract.secret_value(env, "NACOS_ADMIN_PASSWORD"),
        },
    )
    if resp.status_code != 200:
        raise SystemExit(
            f"Nacos admin 初始化失败: HTTP {resp.status_code} {resp.text[:200]}"
        )
    marker.write_text("initialized\n", encoding="utf-8")
    token = _retry(lambda: login(), waits=(1, 2))
    if not token:
        raise SystemExit("admin 初始化成功但登录仍失败,停止")
    return client, token


def _retry(fn, waits=(1, 3, 5)):
    result = None
    for wait in waits:
        try:
            result = fn()
        except (httpx.HTTPError, ValueError):
            result = None
        if result:
            return result
        time.sleep(wait)
    return result


def _sql_quote(value: str) -> str:
    """SQL 字符串字面量转义(凭证仅含 urlsafe 字符,双保险)。"""
    return "'" + value.replace("'", "''") + "'"


def _ensure_pg_resource(res: dict, env: dict[str, str], state: dict) -> str:
    resolved = contract.resolve_pg_resource(res, env)
    db_name, owner, migrator, runtime = [
        resolved[key] for key in ("database", "owner", "migrator", "runtime")
    ]
    state.setdefault("resolved", {}).update(
        {"database": db_name, "owner": owner, "migrator": migrator, "runtime": runtime}
    )

    secrets_dir = Path(env["_ENV_DIR"]) / "secrets"
    with _connect_pg(env) as conn:
        existing = conn.execute(
            "SELECT datname, pg_get_userbyid(datdba) FROM pg_database WHERE datname = %s",
            (db_name,),
        ).fetchone()
        roles_existing = {
            row[0]: row[1:]
            for row in conn.execute(
                "SELECT rolname, rolcanlogin, rolsuper, rolbypassrls, rolcreatedb, "
                "rolcreaterole, rolreplication FROM pg_roles WHERE rolname = ANY(%s)",
                ([owner, migrator, runtime],),
            ).fetchall()
        }

        if existing and existing[1] != owner:
            # 同名但归属不符:不能因为名字一样就当作本项目资源(设计 03)
            print(
                f"[resources] CONFLICT: 数据库 {db_name} 存在但 owner 为 "
                f"{existing[1]}(预期 {owner}),停止该资源"
            )
            return "CONFLICT"

        for role, flags in roles_existing.items():
            if flags[0] != (role != owner) or any(flags[1:]):
                print(f"[resources] CONFLICT: {role} 的 LOGIN/高权限标志不符合声明")
                return "CONFLICT"
        if runtime in roles_existing:
            inherited = conn.execute(
                "SELECT rolname FROM pg_roles WHERE oid <> %s::regrole "
                "AND rolname <> 'pg_database_owner' AND pg_has_role(%s, oid, 'MEMBER')",
                (runtime, runtime),
            ).fetchall()
            if inherited:
                print(f"[resources] CONFLICT: {runtime} 拥有额外角色成员资格")
                return "CONFLICT"
        if res["management"] == "verify":
            if not existing or len(roles_existing) != 3:
                return "CONFLICT"
            member = conn.execute(
                "SELECT pg_has_role(%s, %s, 'MEMBER')", (migrator, owner)
            ).fetchone()
            if not member or not member[0]:
                return "CONFLICT"
            with psycopg.connect(
                host=env["PG_HOST"],
                port=int(env["PG_PORT"]),
                dbname=db_name,
                user=env["POSTGRES_ADMIN_USER"],
                password=contract.secret_value(env, "POSTGRES_ADMIN_PASSWORD"),
                options="-c default_transaction_read_only=on",
            ) as check:
                privilege = check.execute(
                    "SELECT has_schema_privilege(%s, 'public', 'CREATE')", (runtime,)
                ).fetchone()
                if privilege is None or privilege[0]:
                    return "CONFLICT"
            return "VERIFIED"

        if existing and runtime in roles_existing:
            with psycopg.connect(
                host=env["PG_HOST"],
                port=int(env["PG_PORT"]),
                dbname=db_name,
                user=env["POSTGRES_ADMIN_USER"],
                password=contract.secret_value(env, "POSTGRES_ADMIN_PASSWORD"),
                options="-c default_transaction_read_only=on",
            ) as check:
                privilege = check.execute(
                    "SELECT has_schema_privilege(%s, 'public', 'CREATE')", (runtime,)
                ).fetchone()
                if privilege is None or privilege[0]:
                    print(
                        f"[resources] CONFLICT: {runtime} 意外拥有 schema CREATE 权限"
                    )
                    return "CONFLICT"

        created_any = False
        if owner not in roles_existing:
            conn.execute(f'CREATE ROLE "{owner}" NOLOGIN')
            created_any = True
        for role, kind in ((migrator, "migrator"), (runtime, "runtime")):
            if role not in roles_existing:
                password = secrets.token_urlsafe(24)
                # CREATE ROLE/PASSWORD 不支持预编译参数绑定,凭证经字面量转义嵌入
                verifier = conn.pgconn.encrypt_password(
                    password.encode(), role.encode(), b"scram-sha-256"
                ).decode()
                conn.execute(
                    sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(
                        sql.Identifier(role), sql.Literal(verifier)
                    )
                )
                # 凭证只在创建时落盘;已存在角色绝不改密码(轮换是独立流程)
                path = secrets_dir / f"{res['resource_id']}-{kind}.password"
                path.parent.mkdir(exist_ok=True)
                path.write_text(password, encoding="utf-8")
                os.chmod(path, 0o600)
                created_any = True
            else:
                path = secrets_dir / f"{res['resource_id']}-{kind}.password"
                if not path.exists():
                    print(
                        f"[resources] CONFLICT: 角色 {role} 已存在但密码文件缺失"
                        f"(不重置密码),请走独立轮换流程"
                    )
                    return "CONFLICT"

        if not existing:
            conn.execute(f'CREATE DATABASE "{db_name}" OWNER "{owner}"')
        # 迁移/运行账号的库级最小权限;表级权限由各服务迁移负责授予
        conn.execute(
            sql.SQL("GRANT {} TO {}").format(
                sql.Identifier(owner), sql.Identifier(migrator)
            )
        )
        conn.execute(f'GRANT CONNECT, TEMP ON DATABASE "{db_name}" TO "{migrator}"')
        conn.execute(f'GRANT CONNECT ON DATABASE "{db_name}" TO "{runtime}"')
        return "CREATED" if created_any or not existing else "VERIFIED"


def _ensure_nacos_namespace(
    res: dict, env: dict[str, str], client: httpx.Client, token: str, state: dict
) -> str:
    namespace = contract.resolve_namespace(env["PROJECT_CODE"], env["APP_ENV"])
    state.setdefault("resolved", {})["namespace"] = namespace
    console = env["NACOS_CONSOLE_ADDR"].rstrip("/")
    headers = {"accessToken": token}

    # 已存在则核对归属(命名一致即视为本项目资源;不重复创建)
    listed = client.get(f"{console}/v3/console/core/namespace/list", headers=headers)
    if listed.status_code == 200:
        items = listed.json().get("data") or []
        if any(item.get("namespace") == namespace for item in items):
            return "VERIFIED"
    else:
        return "FAILED"
    if res["management"] == "verify":
        return "CONFLICT"
    created = client.post(
        f"{console}/v3/console/core/namespace",
        headers=headers,
        data={
            "customNamespaceId": namespace,
            "namespaceName": namespace,
            "namespaceDesc": f"{env['PROJECT_CODE']} {env['APP_ENV']}",
        },
    )
    if created.status_code == 200 and created.json().get("code") == 0:
        return "CREATED"
    # 部分版本对已存在 namespace 返回非成功码,复查一次避免误报失败
    listed = client.get(f"{console}/v3/console/core/namespace/list", headers=headers)
    items = listed.json().get("data") or [] if listed.status_code == 200 else []
    if any(item.get("namespace") == namespace for item in items):
        return "VERIFIED"
    print(f"[resources] Nacos namespace 创建失败: HTTP {created.status_code}")
    return "FAILED"


def _ensure_nacos_identities(
    res: dict, env: dict[str, str], client: httpx.Client, token: str
) -> str:
    """固定发布/运行身份；只授指定 namespace 的配置或发现权限，不授 console 管理。"""
    console = env["NACOS_CONSOLE_ADDR"].rstrip("/")
    headers = {"accessToken": token}
    namespace = env["NACOS_NAMESPACE"]
    expected = {
        "PUBLISH": {
            (f"{namespace}:DEFAULT_GROUP:config/*", "r"),
            (f"{namespace}:DEFAULT_GROUP:config/*", "w"),
        },
        "RUNTIME": {
            (f"{namespace}:DEFAULT_GROUP:config/*", "r"),
            (f"{namespace}:DEFAULT_GROUP:naming/*", "r"),
            (f"{namespace}:DEFAULT_GROUP:naming/*", "w"),
        },
    }

    def request(method, path, **kwargs):
        response = client.request(
            method, f"{console}/v3/auth/{path}", headers=headers, **kwargs
        )
        body = response.json()
        if response.status_code != 200 or body.get("code") != 0:
            raise ValueError(f"Nacos 身份核对/配置失败: {path}")
        return body.get("data")

    for kind, permissions in expected.items():
        username = env.get(f"NACOS_{kind}_USER")
        if not username or username == env.get("NACOS_ADMIN_USER"):
            raise ValueError("Nacos 发布、运行与管理身份必须独立")
        password = contract.secret_value(env, f"NACOS_{kind}_PASSWORD")
        login = client.post(
            f"{console}/v3/auth/user/login",
            data={"username": username, "password": password},
        )
        if login.status_code != 200 or not login.json().get("accessToken"):
            if res["management"] == "verify":
                return "CONFLICT"
            # 已存在但凭据不一致时 create 会明确失败；绝不 PUT 重置已有密码。
            request("POST", "user", data={"username": username, "password": password})
        role = f"{namespace}-{kind.lower()}"
        roles = (
            request(
                "GET",
                "role/list",
                params={"pageNo": 1, "pageSize": 100, "username": username},
            )
            or {}
        )
        role_names = {item["role"] for item in roles.get("pageItems", [])}
        if role_names - {role}:
            return "CONFLICT"
        if role not in role_names:
            if res["management"] == "verify":
                return "CONFLICT"
            request("POST", "role", data={"role": role, "username": username})
        current = (
            request(
                "GET",
                "permission/list",
                params={"pageNo": 1, "pageSize": 100, "role": role},
            )
            or {}
        )
        present = {
            (item["resource"], item["action"]) for item in current.get("pageItems", [])
        }
        if present - permissions:
            return "CONFLICT"
        for resource, action in sorted(permissions - present):
            if res["management"] == "verify":
                return "CONFLICT"
            request(
                "POST",
                "permission",
                data={"role": role, "resource": resource, "action": action},
            )
    return "VERIFIED"


def _ensure_rabbit_vhost(res: dict, env: dict[str, str], state: dict) -> str:
    if not env.get("RABBIT_HOST"):
        # 未启用 Rabbit 的组合:资源保持 ABSENT,不是失败(设计 03 按组合启用)
        return STATE_ABSENT
    vhost = contract.resolve_namespace(env["PROJECT_CODE"], env["APP_ENV"])
    prefix = contract.resolve_prefix(
        contract.resolve_namespace(env["PROJECT_CODE"], env["APP_ENV"])
    )
    app_user = f"{prefix}_{res['roles']['app']}"
    state.setdefault("resolved", {}).update({"vhost": vhost, "app_user": app_user})
    base = f"http://{env['RABBIT_HOST']}:{env.get('RABBIT_MGMT_PORT', '15672')}"
    auth = (
        env["RABBIT_ADMIN_USER"],
        contract.secret_value(env, "RABBIT_ADMIN_PASSWORD"),
    )
    with httpx.Client(base_url=base, timeout=10, auth=auth) as client:
        existing = client.get(f"/api/vhosts/{vhost}")
        if existing.status_code not in (200, 404):
            return "FAILED"
        if res["management"] == "verify":
            user = client.get(f"/api/users/{app_user}")
            permission = client.get(f"/api/permissions/{vhost}/{app_user}")
            return (
                "VERIFIED"
                if all(
                    response.status_code == 200
                    for response in (existing, user, permission)
                )
                else "CONFLICT"
            )
        if existing.status_code == 200:
            created = "VERIFIED"
        else:
            resp = client.put(f"/api/vhosts/{vhost}")
            if resp.status_code not in (201, 204):
                print(f"[resources] vhost 创建失败: HTTP {resp.status_code}")
                return "FAILED"
            created = "CREATED"
        user = client.get(f"/api/users/{app_user}")
        if user.status_code != 200:
            password = secrets.token_urlsafe(24)
            resp = client.put(
                f"/api/users/{app_user}", json={"password": password, "tags": ""}
            )
            if resp.status_code not in (201, 204):
                return "FAILED"
            path = Path(env["_ENV_DIR"]) / "secrets" / "rabbit-app.password"
            path.parent.mkdir(exist_ok=True)
            path.write_text(f"{app_user}:{password}", encoding="utf-8")
            os.chmod(path, 0o600)
        # 权限幂等授予(重复授予无害)
        permission = client.put(
            f"/api/permissions/{vhost}/{app_user}",
            json={"configure": ".*", "write": ".*", "read": ".*"},
        )
        return created if permission.status_code in (201, 204) else "FAILED"


def load_resources(path: Path) -> list[dict]:
    """校验固定声明并按 depends_on 排序；不接受未知字段或任意执行入口。"""
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict) or set(document) != {"resources"}:
        raise ValueError("资源声明只允许 resources 根字段")
    resources = document["resources"]
    if not isinstance(resources, list):
        raise TypeError("resources 必须为数组")
    by_id = {}
    allowed = {
        "resource_id",
        "component",
        "owner_service",
        "name_binding",
        "management",
        "roles",
        "depends_on",
    }
    for resource in resources:
        if not isinstance(resource, dict) or set(resource) - allowed:
            raise ValueError("资源声明包含未知字段")
        required = allowed - {"roles"}
        if required - set(resource):
            raise ValueError("资源声明缺少固定字段")
        rid = resource["resource_id"]
        if (
            not isinstance(rid, str)
            or not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", rid)
            or rid in by_id
        ):
            raise ValueError("resource_id 必须唯一且为字符串")
        if not isinstance(resource["owner_service"], str) or not re.fullmatch(
            r"[a-z][a-z0-9-]{0,62}", resource["owner_service"]
        ):
            raise ValueError("owner_service 必须为规范服务名")
        if resource["component"] not in ("postgresql", "nacos", "rabbitmq"):
            raise ValueError("不支持的组件类型")
        if resource["management"] not in ("create", "verify"):
            raise ValueError("management 只允许 create/verify")
        if not isinstance(resource["depends_on"], list):
            raise TypeError("depends_on 必须为数组")
        binding_key = {
            "postgresql": "database",
            "nacos": "namespace",
            "rabbitmq": "vhost",
        }[resource["component"]]
        if not isinstance(resource["name_binding"], dict) or set(
            resource["name_binding"]
        ) != {binding_key}:
            raise ValueError("name_binding 与组件不一致")
        expected_roles = (
            {"owner", "migrator", "runtime"}
            if binding_key == "database"
            else ({"app"} if binding_key == "vhost" else set())
        )
        if set(resource.get("roles", {})) != expected_roles:
            raise ValueError("roles 与组件不一致")
        role_values = list(resource.get("roles", {}).values())
        if any(
            not isinstance(value, str)
            or not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value)
            for value in role_values
        ) or len(set(role_values)) != len(role_values):
            raise ValueError("角色名称必须合法且相互独立")
        if (
            binding_key != "database"
            and resource["name_binding"][binding_key] != "default"
        ):
            raise ValueError("namespace/vhost 仅使用项目与环境解析的 default 绑定")
        by_id[rid] = resource
    ordered, visiting, visited = [], set(), set()

    def visit(rid):
        if rid not in by_id or rid in visiting:
            raise ValueError("资源依赖不存在或形成循环")
        if rid in visited:
            return
        visiting.add(rid)
        for dependency in by_id[rid]["depends_on"]:
            visit(dependency)
        visiting.remove(rid)
        visited.add(rid)
        ordered.append(by_id[rid])

    for rid in by_id:
        visit(rid)
    return ordered


def phase_resources(args: argparse.Namespace) -> int:
    env = load_env(Path(args.env_file))
    env["_ENV_DIR"] = str(Path(args.env_file).resolve().parent)
    resources_path = (
        Path(args.resources)
        if args.resources
        else INPUT_ROOT / "infra" / "resources.yaml"
    )
    declarations = load_resources(resources_path)

    state_path = Path(env["_ENV_DIR"]) / "resource-state.json"
    state_all: dict = {}
    if state_path.exists():
        state_all = json.loads(state_path.read_text(encoding="utf-8"))

    nacos_client: httpx.Client | None = None
    nacos_token = ""
    with LocalLock(Path(env["_ENV_DIR"]) / "init.lock"):
        current_results: dict[str, str] = {}
        for res in declarations:
            rid = res["resource_id"]
            component = res["component"]
            state = state_all.setdefault(rid, {})
            try:
                if any(
                    current_results.get(dep) not in ("CREATED", "VERIFIED")
                    for dep in res["depends_on"]
                ):
                    result = "FAILED"
                elif component == "postgresql":
                    result = _ensure_pg_resource(res, env, state)
                elif component == "nacos":
                    if nacos_client is None:
                        nacos_client, nacos_token = _nacos_login(
                            env,
                            bootstrap=res["management"] == "create"
                            and env.get("NACOS_MODE", "self_hosted") == "self_hosted",
                        )
                    result = _ensure_nacos_namespace(
                        res, env, nacos_client, nacos_token, state
                    )
                    if result in ("CREATED", "VERIFIED"):
                        identities = _ensure_nacos_identities(
                            res, env, nacos_client, nacos_token
                        )
                        if identities != "VERIFIED":
                            result = identities
                elif component == "rabbitmq":
                    result = _ensure_rabbit_vhost(res, env, state)
                else:
                    print(f"[resources] 未知组件类型: {component}")
                    result = "FAILED"
            except SystemExit:
                raise
            except (
                psycopg.Error,
                httpx.HTTPError,
                OSError,
                ValueError,
                KeyError,
            ) as exc:
                print(f"[resources] {rid} 执行异常: {type(exc).__name__}")
                result = "FAILED"
            current_results[rid] = result
            state["status"] = result
            state["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
            print(f"[resources] {rid}: {result}")

        state_path.write_text(
            json.dumps(state_all, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    bad = [
        rid
        for rid, status in current_results.items()
        if status in ("CONFLICT", "FAILED")
    ]
    print(f"[resources] 完成;状态文件 {state_path}")
    return 1 if bad else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="环境初始化固定入口(设计 03)")
    parser.add_argument("--env-file", required=True)
    parser.add_argument(
        "--phase", required=True, choices=["prepare-local", "resources"]
    )
    parser.add_argument("--resources", help="覆盖默认资源声明清单(测试用)")
    parser.add_argument("--redis-template", type=Path, help="prepare-local 使用的项目 Redis 模板")
    args = parser.parse_args()
    try:
        if args.phase == "prepare-local":
            return phase_prepare_local(args)
        return phase_resources(args)
    except (OSError, ValueError, TypeError, KeyError, yaml.YAMLError) as exc:
        print(
            f"[init] 初始化拒绝: {type(exc).__name__}，请核对字段、归属与阶段状态",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
