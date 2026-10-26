"""infra/init/run.py — 环境初始化固定入口(设计 03·数据库、账号及命名空间初始化)。

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
import secrets
import subprocess
import sys
import time
from pathlib import Path

import httpx
import psycopg
import yaml

FRAMEWORK_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(FRAMEWORK_ROOT / "config"))
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
}


def phase_prepare_local(args: argparse.Namespace) -> int:
    example = FRAMEWORK_ROOT / "config" / "environments" / "local.env.example"
    local_dir = FRAMEWORK_ROOT / ".local"
    local_dir.mkdir(exist_ok=True)
    target = local_dir / "environment.env"

    if target.exists():
        # 幂等:已存在的环境文件不覆盖(秘密不因重复执行而改变)
        print(f"[prepare-local] 已存在 {target},保持不变")
    else:
        lines = []
        for raw in example.read_text(encoding="utf-8").splitlines():
            key, sep, value = raw.partition("=")
            if not sep:
                lines.append(raw)
                continue
            key, value = key.strip(), value.strip()
            if key in GENERATED_FIELDS and value.startswith("<"):
                value = GENERATED_FIELDS[key]()
            if key == "NACOS_NAMESPACE":
                # namespace 绑定 = {PROJECT_CODE}-{APP_ENV}
                code = next((l.partition("=")[2].strip() for l in lines
                             if l.startswith("PROJECT_CODE=")), "")
                app_env = next((l.partition("=")[2].strip() for l in lines
                                if l.startswith("APP_ENV=")), "")
                value = contract.resolve_namespace(code, app_env)
            lines.append(f"{key}={value}")
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")
        os.chmod(target, 0o600)
        print(f"[prepare-local] 已生成 {target}(含本地随机秘密,勿提交)")

    env = load_env(target)

    # 渲染 Redis 本地配置副本(仓库模板中的占位符替换为真实密码)
    redis_dir = local_dir / "redis"
    redis_dir.mkdir(exist_ok=True)
    template = (FRAMEWORK_ROOT / "infra" / "redis" / "redis.conf").read_text(encoding="utf-8")
    rendered = template.replace("__REDIS_PASSWORD__", env["REDIS_PASSWORD"])
    redis_conf = redis_dir / "redis.conf"
    if not redis_conf.exists():
        redis_conf.write_text(rendered, encoding="utf-8")
        print(f"[prepare-local] 已生成 {redis_conf}")

    # 显式创建项目 external network(infra 与 deploy 两个 Compose 项目共用)
    network = f"{env['PROJECT_CODE']}-net"
    result = subprocess.run(["docker", "network", "inspect", network],
                            capture_output=True, text=True)
    if result.returncode != 0:
        subprocess.run(["docker", "network", "create", network], check=True)
        print(f"[prepare-local] 已创建网络 {network}")
    else:
        print(f"[prepare-local] 网络 {network} 已存在")
    return 0


# ---------------------------------------------------------------------------
# resources
# ---------------------------------------------------------------------------

class LocalLock:
    """本地文件锁:防止并发初始化同一环境(设计 03)。陈旧锁按 PID 存活检测回收。"""

    def __init__(self, path: Path):
        self.path = path

    def __enter__(self):
        self.path.parent.mkdir(exist_ok=True)
        try:
            fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, str(os.getpid()).encode())
            os.close(fd)
            return self
        except FileExistsError:
            try:
                pid = int(self.path.read_text().strip() or "0")
            except ValueError:
                pid = 0
            if pid and _process_alive(pid):
                raise SystemExit(f"另一初始化进程(pid={pid})正在运行,拒绝并发执行")
            print(f"[lock] 回收陈旧锁(pid={pid})")
            self.path.unlink(missing_ok=True)
            return self.__enter__()

    def __exit__(self, *exc):
        self.path.unlink(missing_ok=True)


def _process_alive(pid: int) -> bool:
    try:
        if os.name == "nt":
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}"],
                                 capture_output=True, text=True).stdout
            return str(pid) in out
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _connect_pg(env: dict[str, str]):
    return psycopg.connect(
        host=env["PG_HOST"], port=int(env["PG_PORT"]),
        user=env["POSTGRES_ADMIN_USER"], password=env["POSTGRES_ADMIN_PASSWORD"],
        dbname="postgres", autocommit=True,
    )


def _nacos_login(env: dict[str, str]) -> tuple[httpx.Client, str]:
    """经 console 端口登录获取 accessToken;首次启动时初始化 admin。

    实施发现(T-01/T-02,Nacos 3.2.4):console(8080)与引擎(8848)分离,
    namespace 管理走 console `/v3/console/core/namespace*`,鉴权用
    `accessToken` 请求头;首次无用户时经 `POST /v3/auth/user/admin` 初始化。
    """
    console = env["NACOS_CONSOLE_ADDR"].rstrip("/")
    client = httpx.Client(timeout=10)
    marker = FRAMEWORK_ROOT / ".local" / "nacos-admin-initialized"

    def login() -> str | None:
        resp = client.post(f"{console}/v3/auth/user/login", data={
            "username": env["NACOS_ADMIN_USER"],
            "password": env["NACOS_ADMIN_PASSWORD"]})
        if resp.status_code == 200:
            # 实测:该端点成功响应为 {"accessToken":...,"tokenTtl":...},无 code 字段
            return resp.json().get("accessToken")
        return None

    token = _retry(lambda: login(), waits=(1, 3, 5))
    if token:
        return client, token

    # 登录失败且从未初始化过 → 首次 admin 初始化(console 端口,Nacos 3.x 合同)
    if marker.exists():
        raise SystemExit("Nacos admin 登录失败但已标记初始化过;不重置密码,请人工核对凭证")
    resp = client.post(f"{console}/v3/auth/user/admin", data={
        "username": env["NACOS_ADMIN_USER"],
        "password": env["NACOS_ADMIN_PASSWORD"]})
    if resp.status_code != 200:
        raise SystemExit(f"Nacos admin 初始化失败: HTTP {resp.status_code} {resp.text[:200]}")
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
        except Exception:
            result = None
        if result:
            return result
        time.sleep(wait)
    return result


def _sql_quote(value: str) -> str:
    """SQL 字符串字面量转义(凭证仅含 urlsafe 字符,双保险)。"""
    return "'" + value.replace("'", "''") + "'"


def _ensure_pg_resource(res: dict, env: dict[str, str], state: dict) -> str:
    prefix = contract.resolve_prefix(env["PROJECT_CODE"])
    binding = res["name_binding"]
    roles = res["roles"]
    db_name = f"{prefix}_{binding['database']}"
    owner = f"{prefix}_{roles['owner']}"
    migrator = f"{prefix}_{roles['migrator']}"
    runtime = f"{prefix}_{roles['runtime']}"
    state.setdefault("resolved", {}).update(
        {"database": db_name, "owner": owner, "migrator": migrator, "runtime": runtime})

    secrets_dir = FRAMEWORK_ROOT / ".local" / "secrets"
    with _connect_pg(env) as conn:
        existing = conn.execute(
            "SELECT datname, pg_get_userbyid(datdba) FROM pg_database WHERE datname = %s",
            (db_name,)).fetchone()
        roles_existing = {row[0] for row in conn.execute(
            "SELECT rolname FROM pg_roles WHERE rolname = ANY(%s)",
            ([owner, migrator, runtime],)).fetchall()}

        if existing and existing[1] != owner:
            # 同名但归属不符:不能因为名字一样就当作本项目资源(设计 03)
            print(f"[resources] CONFLICT: 数据库 {db_name} 存在但 owner 为 "
                  f"{existing[1]}(预期 {owner}),停止该资源")
            return "CONFLICT"

        created_any = False
        if owner not in roles_existing:
            conn.execute(f'CREATE ROLE "{owner}" NOLOGIN')
            created_any = True
        for role, kind in ((migrator, "migrator"), (runtime, "runtime")):
            if role not in roles_existing:
                password = secrets.token_urlsafe(24)
                # CREATE ROLE/PASSWORD 不支持预编译参数绑定,凭证经字面量转义嵌入
                conn.execute(
                    f'CREATE ROLE "{role}" LOGIN PASSWORD {_sql_quote(password)}')
                # 凭证只在创建时落盘;已存在角色绝不改密码(轮换是独立流程)
                path = secrets_dir / f"{res['resource_id']}-{kind}.password"
                path.parent.mkdir(exist_ok=True)
                path.write_text(password, encoding="utf-8")
                os.chmod(path, 0o600)
                created_any = True
            else:
                path = secrets_dir / f"{res['resource_id']}-{kind}.password"
                if not path.exists():
                    print(f"[resources] CONFLICT: 角色 {role} 已存在但密码文件缺失"
                          f"(不重置密码),请走独立轮换流程")
                    return "CONFLICT"

        if not existing:
            conn.execute(f'CREATE DATABASE "{db_name}" OWNER "{owner}"')
        # 迁移/运行账号的库级最小权限;表级权限由各服务迁移负责授予
        conn.execute(f'GRANT CONNECT, TEMP ON DATABASE "{db_name}" TO "{migrator}"')
        conn.execute(f'GRANT CONNECT ON DATABASE "{db_name}" TO "{runtime}"')
        return "CREATED" if created_any or not existing else "VERIFIED"


def _ensure_nacos_namespace(res: dict, env: dict[str, str], client: httpx.Client,
                            token: str, state: dict) -> str:
    namespace = contract.resolve_namespace(env["PROJECT_CODE"], env["APP_ENV"])
    state.setdefault("resolved", {})["namespace"] = namespace
    console = env["NACOS_CONSOLE_ADDR"].rstrip("/")
    headers = {"accessToken": token}

    # 已存在则核对归属(命名一致即视为本项目资源;不重复创建)
    listed = client.get(f"{console}/v3/console/core/namespace/list",
                        headers=headers)
    if listed.status_code == 200:
        items = listed.json().get("data") or []
        if any(item.get("namespace") == namespace for item in items):
            return "VERIFIED"
    created = client.post(f"{console}/v3/console/core/namespace",
                          headers=headers,
                          data={"customNamespaceId": namespace,
                                "namespaceName": namespace,
                                "namespaceDesc": f"{env['PROJECT_CODE']} {env['APP_ENV']}"})
    if created.status_code == 200 and created.json().get("code") == 0:
        return "CREATED"
    # 部分版本对已存在 namespace 返回非成功码,复查一次避免误报失败
    listed = client.get(f"{console}/v3/console/core/namespace/list", headers=headers)
    items = listed.json().get("data") or [] if listed.status_code == 200 else []
    if any(item.get("namespace") == namespace for item in items):
        return "VERIFIED"
    print(f"[resources] Nacos namespace 创建失败: HTTP {created.status_code} "
          f"{created.text[:200]}")
    return "FAILED"


def _ensure_rabbit_vhost(res: dict, env: dict[str, str], state: dict) -> str | None:
    if not env.get("RABBIT_HOST"):
        # 未启用 Rabbit 的组合:资源保持 ABSENT,不是失败(设计 03 按组合启用)
        return STATE_ABSENT
    vhost = contract.resolve_namespace(env["PROJECT_CODE"], env["APP_ENV"])
    prefix = contract.resolve_prefix(env["PROJECT_CODE"])
    app_user = f"{prefix}_{res['roles']['app']}"
    state.setdefault("resolved", {}).update({"vhost": vhost, "app_user": app_user})
    base = f"http://{env['RABBIT_HOST']}:{env.get('RABBIT_MGMT_PORT', '15672')}"
    auth = (env["RABBIT_ADMIN_USER"], env["RABBIT_ADMIN_PASSWORD"])
    with httpx.Client(timeout=10, auth=auth) as client:
        existing = client.get(f"/api/vhosts/{vhost}")
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
            resp = client.put(f"/api/users/{app_user}", json={
                "password": password, "tags": ""})
            if resp.status_code not in (201, 204):
                return "FAILED"
            path = FRAMEWORK_ROOT / ".local" / "secrets" / "rabbit-app.password"
            path.parent.mkdir(exist_ok=True)
            path.write_text(f"{app_user}:{password}", encoding="utf-8")
            os.chmod(path, 0o600)
        # 权限幂等授予(重复授予无害)
        client.put(f"/api/permissions/{vhost}/{app_user}", json={
            "configure": ".*", "write": ".*", "read": ".*"})
        return created


def phase_resources(args: argparse.Namespace) -> int:
    env = load_env(Path(args.env_file))
    resources_path = Path(args.resources) if args.resources \
        else FRAMEWORK_ROOT / "infra" / "resources.yaml"
    declarations = yaml.safe_load(resources_path.read_text(encoding="utf-8"))["resources"]

    state_path = FRAMEWORK_ROOT / ".local" / "resource-state.json"
    state_all: dict = {}
    if state_path.exists():
        state_all = json.loads(state_path.read_text(encoding="utf-8"))

    nacos_client = nacos_token = None
    with LocalLock(FRAMEWORK_ROOT / ".local" / "init.lock"):
        for res in declarations:
            rid = res["resource_id"]
            component = res["component"]
            state = state_all.setdefault(rid, {})
            try:
                if component == "postgresql":
                    result = _ensure_pg_resource(res, env, state)
                elif component == "nacos":
                    if nacos_client is None:
                        nacos_client, nacos_token = _nacos_login(env)
                    result = _ensure_nacos_namespace(res, env, nacos_client,
                                                     nacos_token, state)
                elif component == "rabbitmq":
                    result = _ensure_rabbit_vhost(res, env, state)
                else:
                    print(f"[resources] 未知组件类型: {component}")
                    result = "FAILED"
            except SystemExit:
                raise
            except Exception as exc:  # 记录失败,保留已成功资源(设计 03)
                print(f"[resources] {rid} 执行异常: {exc}")
                result = "FAILED"
            state["status"] = result
            state["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
            print(f"[resources] {rid}: {result}")

        state_path.write_text(json.dumps(state_all, ensure_ascii=False, indent=2),
                              encoding="utf-8")

    bad = [rid for rid, s in state_all.items() if s.get("status") in ("CONFLICT", "FAILED")]
    print(f"[resources] 完成;状态文件 {state_path}")
    return 1 if bad else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="环境初始化固定入口(设计 03)")
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--phase", required=True, choices=["prepare-local", "resources"])
    parser.add_argument("--resources", help="覆盖默认资源声明清单(测试用)")
    args = parser.parse_args()
    if args.phase == "prepare-local":
        return phase_prepare_local(args)
    return phase_resources(args)


if __name__ == "__main__":
    raise SystemExit(main())
