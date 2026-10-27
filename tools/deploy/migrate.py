#!/usr/bin/env python3
"""数据库迁移协调入口(实施计划 T-11A,设计 10《数据库迁移与升级设计》)。

职责合同(设计 10 §1、§2;设计 03 §6):
- 普通服务的 Flyway/Alembic 迁移统一入口:固定迁移目标、历史校验、schema 检查;
- 专用控制连接持有 ``pg_advisory_lock(hashtext(目标库名))``,整条迁移链
  (validate -> migrate -> verify)结束前不释放;Flyway 自身锁不能替代该互斥;
- 控制连接意外断开时立即停止调度新步骤,状态标 ``unknown``(待核对);
- 状态文件 ``.local/migration-state/<target>.json`` 记录 running/done/failed/unknown
  与步骤;再次执行时若上次为 unknown(或进程中断遗留的 running),先核对远端事实
  (flyway_schema_history / alembic_version 实际内容 vs 状态文件),打印核对结果,
  并要求 ``--confirm-unknown`` 才继续;不能仅因锁已释放就重跑;
- flyway 步骤经 docker run flyway 镜像执行,alembic 步骤经 python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a
  容器(容器内安装固定版本 alembic)执行;migrator 凭证从密码文件读取;
- 任一步骤失败即停止(状态 failed、退出码非 0);不重试、不 clean、不 repair。

账号模型(设计 03 §6):每服务库由 NOLOGIN owner 持有对象;migrator 具备 DDL
能力,经连接参数 ``options=-c role=<owner>`` 以 owner 身份执行 DDL;runtime 无 DDL。
本入口不创建账号(归 infra 初始化入口,T-02),只使用既有凭证。

resources.yaml 的权威 schema 由 T-02 定义;本入口按如下宽松形状读取,文件不存在
或未命中目标时必须通过 CLI 显式覆盖::

    resources:
      - resource_id: identity-db        # 资源逻辑名(--target)
        component: postgres
        database: identity              # 物理库名
        owner_role: identity_owner      # NOLOGIN owner
        migrator_role: identity_migrator
        runtime_role: identity_runtime

环境值(地址/密码文件路径)来自 ``--env-file``(KEY=VALUE,每行一项,支持注释),
可识别键:PGHOST、PGPORT、JDBC_HOST、JDBC_PORT、DOCKER_NETWORK、
MIGRATOR_PASSWORD_FILE;秘密不写入仓库。CLI 显式参数优先于 env-file。

退出码::
    0  成功 / 目标已是最新 / --check 校验通过
    1  迁移步骤失败(状态 failed)
    2  参数或配置错误(缺文件、连不上库、坏的目标名等)
    3  迁移锁被其他进程持有(争锁失败/等待超时)
    4  控制连接丢失(状态 unknown,待核对)
    5  上次状态未知,需人工核对后加 --confirm-unknown
    6  存在待执行迁移,需 --yes 确认
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse
import uuid
import warnings
from pathlib import Path
from typing import Any

import psycopg
from alembic.script import ScriptDirectory
from alembic.script.revision import Revision, RevisionMap

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/config"))
import contract

# 固定镜像与工具版本(设计 01:依赖不可浮动;实施时已在 Docker Hub / PyPI 核实存在)
FLYWAY_IMAGE = "flyway/flyway:11.20.3@sha256:01605f443e1a891b3762f66e66d92403c33b6e0c29099ad550af94b6c1cbc3c1"
PYTHON_IMAGE = "python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a"
ALEMBIC_VERSION = "1.20.1"
SQLALCHEMY_VERSION = "2.1.6"
PSYCOPG_VERSION = "3.3.6"

# DDL 会话级超时(设计 10 §2:lock_timeout=5s、statement_timeout=5min,超时视为失败)
PG_SESSION_OPTIONS_TIMEOUTS = "-c lock_timeout=5s -c statement_timeout=5min"

EXIT_OK = 0
EXIT_STEP_FAILED = 1
EXIT_USAGE = 2
EXIT_LOCK_BUSY = 3
EXIT_CONTROL_LOST = 4
EXIT_NEED_CONFIRM = 5
EXIT_NEED_YES = 6

STATUS_RUNNING = "running"
STATUS_DONE = "done"
STATUS_FAILED = "failed"
STATUS_UNKNOWN = "unknown"

# Flyway 默认命名 V<version>__<description>.sql(设计 10:禁止改已执行脚本)
FLYWAY_FILE_RE = re.compile(r"^V(\d+(?:\.\d+)?)__.*\.sql$", re.IGNORECASE)
# 目标逻辑名同时用作状态文件名,限定安全字符
TARGET_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")

SCRIPT_PATH = Path(__file__).resolve()
FRAMEWORK_ROOT = SCRIPT_PATH.parents[2]
INPUT_ROOT = FRAMEWORK_ROOT / "template" if (FRAMEWORK_ROOT / "template").is_dir() else FRAMEWORK_ROOT
DEFAULT_RESOURCES_PATH = INPUT_ROOT / "infra" / "resources.yaml"
DEFAULT_STATE_DIR = FRAMEWORK_ROOT / ".local" / "migration-state"


class UsageError(Exception):
    """参数或配置错误(退出码 2)。"""


class ControlLostError(Exception):
    """专用控制连接断开:立即停止调度新步骤,状态标 unknown(退出码 4)。"""


class StepFailedError(Exception):
    """迁移链中某一步骤失败:停止,状态 failed(退出码 1)。"""


def now_iso() -> str:
    """统一使用 UTC ISO-8601 时间戳记录状态。"""
    return dt.datetime.now(dt.UTC).isoformat(timespec="seconds")


def to_posix(path: Path) -> str:
    """docker -v 挂载参数使用正斜杠形式,避免 Windows 反斜杠歧义。"""
    return str(path.resolve()).replace("\\", "/")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


# ---------------------------------------------------------------------------
# 配置解析
# ---------------------------------------------------------------------------


def parse_env_file(path: Path) -> dict[str, str]:
    """与初始化/发布共用环境合同，不维护 PGHOST 等第二套字段。"""
    env = contract.parse_env_file(path)
    issues = contract.validate_env(env)
    if issues:
        raise UsageError("; ".join(issues))
    return env.values


def load_resource(
    resources_path: Path, target: str, env: dict[str, str]
) -> dict[str, Any] | None:
    """读取 infra/resources.yaml 中命中的 postgresql 资源声明(T-02 权威 schema)。

    权威字段为 ``component: postgresql`` + ``name_binding.database`` +
    ``roles.{owner,migrator,runtime}``;此处提取迁移所需字段并归一化,
    文件不存在或未命中返回 None,由调用方要求 CLI 显式覆盖。
    """
    if not resources_path.exists():
        return None
    try:
        import yaml
    except ImportError as exc:  # pragma: no cover - 工具环境已包含 pyyaml
        raise UsageError("缺少 pyyaml 依赖,无法读取 resources.yaml") from exc
    data = yaml.safe_load(resources_path.read_text(encoding="utf-8")) or {}
    for item in data.get("resources") or []:
        if not (isinstance(item, dict) and item.get("resource_id") == target):
            continue
        component = item.get("component")
        if component != "postgresql":
            continue
        resolved = contract.resolve_pg_resource(item, env)
        return {
            "database": resolved["database"],
            "owner_role": resolved["owner"],
            "migrator_role": resolved["migrator"],
            "runtime_role": resolved["runtime"],
        }
    return None


def read_password_file(path: Path) -> str:
    """从密码文件读取凭证(去除首尾空白);秘密不进参数默认值、不落仓库。"""
    if not path.exists():
        raise UsageError(f"密码文件不存在: {path}")
    value = path.read_text(encoding="utf-8").strip()
    if not value:
        raise UsageError(f"密码文件为空: {path}")
    return value


@dataclasses.dataclass
class MigrationConfig:
    """一次迁移执行的全部已解析配置(秘密仅保留在进程内)。"""

    target: str
    tool: str
    db_name: str
    db_user: str
    password: str = dataclasses.field(repr=False)
    owner_role: str | None
    db_host: str
    db_port: int
    jdbc_host: str
    jdbc_port: int
    docker_network: str | None
    migrations_dir: Path
    state_dir: Path
    step_timeout: int
    control_connection: psycopg.Connection | None = dataclasses.field(
        default=None, repr=False
    )
    runtime_role: str | None = None

    def connect_kwargs(self) -> dict[str, Any]:
        """控制连接/核对查询的 psycopg 连接参数(直连目标库,host 视角)。"""
        return {
            "dbname": self.db_name,
            "user": self.db_user,
            "password": self.password,
            "host": self.db_host,
            "port": self.db_port,
            "connect_timeout": 10,
        }

    def session_options(self) -> str:
        """容器内工具连接的 PG 会话参数:以 owner 身份执行 DDL 并套用 DDL 超时。"""
        parts = []
        if self.owner_role:
            parts.append(f"-c role={self.owner_role}")
        parts.append(PG_SESSION_OPTIONS_TIMEOUTS)
        return " ".join(parts)


def resolve_config(args: argparse.Namespace, env: dict[str, str]) -> MigrationConfig:
    """合并 CLI 参数 > env-file > resources.yaml,产出执行配置。"""
    if not TARGET_RE.match(args.target):
        raise UsageError(f"目标逻辑名含非法字符: {args.target}")
    if args.tool not in ("flyway", "alembic"):
        raise UsageError(f"不支持的迁移工具: {args.tool}")

    resource = (
        load_resource(args.resources, args.target, env)
        if env.get("PROJECT_CODE")
        else None
    )

    db_name = args.db_name or (resource or {}).get("database")
    db_user = args.db_user or (resource or {}).get("migrator_role")
    owner_role = args.owner_role or (resource or {}).get("owner_role")
    env_dir = (
        Path(args.env_file).resolve().parent
        if args.env_file
        else FRAMEWORK_ROOT / ".local"
    )
    password_file = (
        args.password_file or env_dir / "secrets" / f"{args.target}-migrator.password"
    )
    if not db_name:
        raise UsageError(
            f"无法从 {args.resources} 解析目标 {args.target} 的库名,请用 --db-name 显式指定"
        )
    if not db_user:
        raise UsageError(
            f"无法从 {args.resources} 解析目标 {args.target} 的 migrator 账号,请用 --db-user 显式指定"
        )
    if not password_file:
        raise UsageError(
            "缺少 migrator 密码文件:请用 --password-file 或 env-file 的 MIGRATOR_PASSWORD_FILE 指定"
        )

    migrations_dir = (
        Path(args.migrations_dir).resolve() if args.migrations_dir else None
    )
    if migrations_dir is None or not migrations_dir.is_dir():
        raise UsageError(f"迁移目录不存在或不是目录: {args.migrations_dir}")

    return MigrationConfig(
        target=args.target,
        tool=args.tool,
        db_name=str(db_name),
        db_user=str(db_user),
        password=read_password_file(Path(password_file)),
        owner_role=str(owner_role) if owner_role else None,
        db_host=args.db_host or env.get("PG_HOST") or "127.0.0.1",
        db_port=int(args.db_port or env.get("PG_PORT") or 5432),
        jdbc_host=args.jdbc_host
        or (
            "postgres"
            if env.get("APP_ENV") == "local"
            and env.get("PG_MODE", "self_hosted") == "self_hosted"
            else args.db_host or env.get("PG_HOST") or "127.0.0.1"
        ),
        jdbc_port=int(
            args.jdbc_port
            or (
                5432
                if env.get("APP_ENV") == "local"
                and env.get("PG_MODE", "self_hosted") == "self_hosted"
                else args.db_port or env.get("PG_PORT") or 5432
            )
        ),
        docker_network=args.docker_network
        or env.get("INFRA_NETWORK")
        or (
            f"{env['PROJECT_CODE']}-{env['APP_ENV']}-net"
            if env.get("PROJECT_CODE")
            else None
        ),
        migrations_dir=migrations_dir,
        state_dir=Path(args.state_dir).resolve()
        if args.state_dir
        else env_dir / "migration-state",
        step_timeout=args.step_timeout,
        runtime_role=(resource or {}).get("runtime_role"),
    )


# ---------------------------------------------------------------------------
# 状态文件
# ---------------------------------------------------------------------------


def state_path(cfg: MigrationConfig) -> Path:
    return cfg.state_dir / f"{cfg.target}.json"


def load_state(cfg: MigrationConfig) -> dict[str, Any] | None:
    path = state_path(cfg)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        # 状态文件损坏同样视为待核对,不允许直接重跑
        return {"status": STATUS_UNKNOWN, "steps": [], "corrupt": str(exc)}
    if not isinstance(data, dict) or data.get("status") not in (
        STATUS_RUNNING,
        STATUS_DONE,
        STATUS_FAILED,
        STATUS_UNKNOWN,
    ):
        return {"status": STATUS_UNKNOWN, "steps": [], "corrupt": "状态文件结构不合法"}
    return data


def save_state(cfg: MigrationConfig, state: dict[str, Any]) -> None:
    """原子写入状态文件(先写 .tmp 再替换),目录按需创建。"""
    path = state_path(cfg)
    path.parent.mkdir(parents=True, exist_ok=True)
    state["updated_at"] = now_iso()
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def remove_state(cfg: MigrationConfig) -> None:
    path = state_path(cfg)
    if path.exists():
        path.unlink()


def new_run_state(
    cfg: MigrationConfig, previous: dict[str, Any] | None
) -> dict[str, Any]:
    """开始新一次运行:归档上一次运行,重置步骤列表。"""
    state: dict[str, Any] = {
        "target": cfg.target,
        "tool": cfg.tool,
        "db_name": cfg.db_name,
        "status": STATUS_RUNNING,
        "run_started_at": now_iso(),
        "steps": [],
        "facts": (previous or {}).get("facts")
        or (previous or {}).get("last_run", {}).get("facts", {}),
    }
    if previous:
        state["last_run"] = {
            "status": previous.get("status"),
            "run_started_at": previous.get("run_started_at"),
            "steps": previous.get("steps", []),
            "facts": previous.get("facts", {}),
        }
    return state


def record_step(
    state: dict[str, Any], name: str, started_at: str, status: str, detail: str
) -> None:
    """记录一个步骤的起止与输出摘要(仅保留尾部,避免状态文件膨胀)。"""
    state["steps"].append(
        {
            "name": name,
            "status": status,
            "started_at": started_at,
            "finished_at": now_iso(),
            "detail": detail[-4000:],
        }
    )


# ---------------------------------------------------------------------------
# 本地迁移文件与远端事实
# ---------------------------------------------------------------------------


def scan_flyway_files(cfg: MigrationConfig) -> list[dict[str, Any]]:
    """扫描本地 Flyway SQL 文件(V 命名),返回按版本排序的清单(含 SHA-256)。"""
    items: list[dict[str, Any]] = []
    for entry in sorted(cfg.migrations_dir.iterdir()):
        match = FLYWAY_FILE_RE.match(entry.name)
        if entry.is_file() and match:
            items.append(
                {
                    "version": match.group(1),
                    "sort_key": tuple(int(p) for p in match.group(1).split(".")),
                    "file": entry.name,
                    "sha256": sha256_file(entry),
                }
            )
    items.sort(key=lambda item: item["sort_key"])
    return items


def scan_alembic_versions(cfg: MigrationConfig) -> dict[str, dict[str, Any]]:
    """由 Alembic 读取版本模块和拓扑；本层仅记录发行文件摘要。"""
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", UserWarning)
            scripts = ScriptDirectory(str(cfg.migrations_dir))
            head = scripts.get_current_head()
            if head is None:
                raise UsageError("Alembic 迁移目录没有 revision")
            return {
                script.revision: {
                    "file": str(Path(script.path).relative_to(cfg.migrations_dir)),
                    "sha256": sha256_file(Path(script.path)),
                    "down": script.down_revision,
                    "dependencies": script.dependencies,
                }
                for script in scripts.walk_revisions()
            }
    except UsageError:
        raise
    except Exception as exc:
        # 版本模块是可信发行源码；异常可能带连接参数，不回显源码/环境。
        raise UsageError("Alembic 无法加载单一有效版本图，请检查发行迁移文件") from exc


def alembic_head(revisions: dict[str, dict[str, Any]]) -> str:
    """版本关系交给官方 RevisionMap，支持类型注解、分支合并与依赖。"""
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", UserWarning)
            graph = RevisionMap(
                lambda: (
                    Revision(rev, info["down"], dependencies=info.get("dependencies"))
                    for rev, info in revisions.items()
                )
            )
            head = graph.get_current_head()
            if head is None:
                raise UsageError("Alembic 迁移目录没有 revision")
            return head
    except UsageError:
        raise
    except Exception as exc:
        raise UsageError("Alembic 要求单一有效 head") from exc


def read_remote_facts(cfg: MigrationConfig, conn: psycopg.Connection) -> dict[str, Any]:
    """读取远端事实(flyway_schema_history / alembic_version 实际内容)。"""
    if cfg.tool == "flyway":
        exists = conn.execute(
            "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_tables "
            "WHERE schemaname = 'public' AND tablename = 'flyway_schema_history')"
        ).fetchone()
        if not exists or not exists[0]:
            return {"kind": "flyway", "history": []}
        rows = conn.execute(
            "SELECT version, description, checksum, success, installed_on::text "
            "FROM public.flyway_schema_history ORDER BY installed_rank"
        ).fetchall()
        return {
            "kind": "flyway",
            "history": [
                {
                    "version": r[0],
                    "description": r[1],
                    "checksum": r[2],
                    "success": r[3],
                    "installed_on": r[4],
                }
                for r in rows
            ],
        }
    exists = conn.execute(
        "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_tables "
        "WHERE schemaname = 'public' AND tablename = 'alembic_version')"
    ).fetchone()
    if not exists or not exists[0]:
        return {"kind": "alembic", "version": None, "revision_shas": {}}
    rows = conn.execute("SELECT version_num FROM public.alembic_version").fetchall()
    if len(rows) > 1:
        raise UsageError("远端 Alembic 存在多个当前版本，须先核对迁移状态")
    return {
        "kind": "alembic",
        "version": rows[0][0] if rows else None,
        "revision_shas": {},
    }


def open_short_connection(cfg: MigrationConfig) -> psycopg.Connection:
    """短连接(核对/查询用);连接失败属于配置错误。"""
    try:
        return psycopg.connect(**cfg.connect_kwargs())
    except psycopg.Error as exc:
        raise UsageError(f"无法连接目标库 {cfg.db_name}: {exc}") from exc


def compute_pending(cfg: MigrationConfig, remote: dict[str, Any]) -> list[str]:
    """根据远端事实计算待执行迁移(只描述,不修改)。"""
    if cfg.tool == "flyway":
        applied = {h["version"] for h in remote["history"] if h["success"]}
        return [
            item["version"]
            for item in scan_flyway_files(cfg)
            if item["version"] not in applied
        ]
    head = alembic_head(scan_alembic_versions(cfg))
    return (
        []
        if remote["version"] == head
        else [f"{remote['version'] or '<空>'} -> {head}"]
    )


# ---------------------------------------------------------------------------
# 迁移锁(专用控制连接)
# ---------------------------------------------------------------------------


def acquire_lock(cfg: MigrationConfig, wait_seconds: float) -> psycopg.Connection:
    """以专用控制连接获取会话级 advisory lock;等待超时抛 UsageError 语义由调用方处理。

    锁键 = ``pg_advisory_lock(hashtext(目标库名))``,与所有自动/应急入口共用;
    控制连接 autocommit,锁持续到连接关闭(整条迁移链结束)。
    """
    deadline = time.monotonic() + max(0.0, wait_seconds)
    last_error: Exception | None = None
    while True:
        conn: psycopg.Connection | None = None
        try:
            conn = psycopg.connect(**cfg.connect_kwargs(), autocommit=True)
            got = conn.execute(
                "SELECT pg_try_advisory_lock(hashtext(%s))", (cfg.db_name,)
            ).fetchone()
            if got and got[0]:
                return conn
            conn.close()
        except psycopg.Error as exc:
            if conn is not None:
                conn.close()
            last_error = exc
            raise UsageError(f"控制连接失败,无法申请迁移锁: {exc}") from exc
        if time.monotonic() >= deadline:
            if last_error:
                raise UsageError(f"控制连接失败,无法申请迁移锁: {last_error}")
            raise TimeoutError("lock-busy")
        time.sleep(0.5)


def control_health_check(conn: psycopg.Connection) -> None:
    """步骤间检查专用控制连接存活;断开立即停止调度新步骤(设计 10 §1)。"""
    try:
        conn.execute("SELECT 1").fetchone()
    except psycopg.OperationalError as exc:
        raise ControlLostError(f"专用控制连接已断开: {exc}") from exc


# ---------------------------------------------------------------------------
# 容器步骤执行(flyway / alembic)
# ---------------------------------------------------------------------------


def docker_run(
    cfg: MigrationConfig,
    image: str,
    args: list[str],
    volumes: list[str],
    envs: dict[str, str],
) -> tuple[int, str]:
    """受控秘密文件注入，命令/输出脱敏；丢控制连接或超时后终止自己的容器。"""
    cfg.state_dir.mkdir(parents=True, exist_ok=True)
    container_name = f"ken-migration-{uuid.uuid4().hex[:12]}"
    with tempfile.TemporaryDirectory(prefix=".secret-", dir=cfg.state_dir) as folder:
        secret_env = Path(folder) / "container.env"
        if any("\n" in value or "\r" in value for value in envs.values()):
            raise UsageError("迁移容器环境值不得包含换行")
        secret_env.write_text(
            "".join(f"{key}={value}\n" for key, value in envs.items()), encoding="utf-8"
        )
        secret_env.chmod(0o600)
        cmd = [
            "docker",
            "run",
            "--rm",
            "--name",
            container_name,
            "--env-file",
            str(secret_env),
        ]
        if cfg.docker_network:
            cmd += ["--network", cfg.docker_network]
        for volume in volumes:
            cmd += ["-v", volume]
        cmd += [image, *args]
        try:
            process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        except FileNotFoundError as exc:
            raise UsageError("未找到 docker 命令,请确认管理机已安装 Docker") from exc
        deadline = time.monotonic() + cfg.step_timeout
        try:
            while True:
                if cfg.control_connection is not None:
                    control_health_check(cfg.control_connection)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise StepFailedError(
                        f"迁移容器步骤超时(>{cfg.step_timeout}s)，结果待核对"
                    )
                try:
                    stdout, stderr = process.communicate(timeout=min(1, remaining))
                    output = f"{stdout or ''}{stderr or ''}".strip()
                    for secret in (
                        cfg.password,
                        urllib.parse.quote(cfg.password, safe=""),
                    ):
                        if secret:
                            output = output.replace(secret, "<redacted>")
                    return process.returncode, output
                except subprocess.TimeoutExpired:
                    continue
        except BaseException:
            subprocess.run(
                ["docker", "rm", "-f", container_name],
                capture_output=True,
                timeout=30,
                check=False,
            )
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=30)
            raise


def flyway_jdbc_url(cfg: MigrationConfig) -> str:
    """Flyway JDBC URL;options 参数同时完成 SET ROLE(owner)与 DDL 超时设置。"""
    query = urllib.parse.urlencode({"options": cfg.session_options()})
    return f"jdbc:postgresql://{cfg.jdbc_host}:{cfg.jdbc_port}/{cfg.db_name}?{query}"


def run_flyway(cfg: MigrationConfig, command: str) -> tuple[bool, str]:
    """通过 flyway 镜像执行子命令(validate/migrate);不使用 clean/repair。

    validate 忽略 ``*:pending``:待执行迁移是正常状态,由本入口的 pending 计算
    与 ``--yes`` 把关;已应用脚本的历史/摘要篡改仍会校验失败。
    """
    volumes = [f"{to_posix(cfg.migrations_dir)}:/flyway/sql:ro"]
    envs = {
        "FLYWAY_URL": flyway_jdbc_url(cfg),
        "FLYWAY_USER": cfg.db_user,
        "FLYWAY_PASSWORD": cfg.password,
        "FLYWAY_LOCATIONS": "filesystem:/flyway/sql",
        "FLYWAY_CONNECT_RETRIES": "5",
    }
    if command == "validate":
        envs["FLYWAY_IGNORE_MIGRATION_PATTERNS"] = "*:pending"
    if cfg.runtime_role:
        envs["FLYWAY_PLACEHOLDERS_RUNTIME_ROLE"] = cfg.runtime_role
    code, output = docker_run(cfg, FLYWAY_IMAGE, [command], volumes, envs)
    return code == 0, output


def alembic_database_url(cfg: MigrationConfig) -> str:
    """alembic env.py 使用的连接 URL(psycopg 驱动,options 完成 SET ROLE 与超时)。"""
    auth = (
        f"{urllib.parse.quote(cfg.db_user)}:{urllib.parse.quote(cfg.password, safe='')}"
    )
    query = urllib.parse.quote(cfg.session_options())
    return f"postgresql+psycopg://{auth}@{cfg.jdbc_host}:{cfg.jdbc_port}/{cfg.db_name}?options={query}"


def run_alembic(cfg: MigrationConfig, alembic_args: list[str]) -> tuple[bool, str]:
    """通过 python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a 容器执行 alembic 命令(容器内安装固定版本依赖)。"""
    volumes = [f"{to_posix(cfg.migrations_dir)}:/migrations:ro"]
    envs = {
        "DATABASE_URL": alembic_database_url(cfg),
        "PIP_DISABLE_PIP_VERSION_CHECK": "1",
    }
    if cfg.runtime_role:
        envs["DB_RUNTIME_ROLE"] = cfg.runtime_role
    install = (
        f"pip install --no-cache-dir --quiet --root-user-action=ignore "
        f"alembic=={ALEMBIC_VERSION} SQLAlchemy=={SQLALCHEMY_VERSION} 'psycopg[binary]=={PSYCOPG_VERSION}'"
    )
    script = f'{install} && cd /migrations && exec alembic "$@"'
    args = ["sh", "-c", script, "alembic", *alembic_args]
    code, output = docker_run(cfg, PYTHON_IMAGE, args, volumes, envs)
    return code == 0, output


# ---------------------------------------------------------------------------
# 未知状态核对(重启恢复)
# ---------------------------------------------------------------------------


def format_facts_for_print(
    cfg: MigrationConfig, remote: dict[str, Any], local_summary: list[str]
) -> list[str]:
    """把远端事实格式化为可人工审阅的核对行。"""
    lines = []
    if remote["kind"] == "flyway":
        if not remote["history"]:
            lines.append("[核对] 远端 flyway_schema_history:不存在(空库或从未迁移)")
        for row in remote["history"]:
            lines.append(
                "[核对] 远端 flyway_schema_history: "
                f"version={row['version']} description={row['description']} "
                f"checksum={row['checksum']} success={row['success']} installed_on={row['installed_on']}"
            )
    else:
        lines.append(
            f"[核对] 远端 alembic_version: {remote['version'] or '<表为空或不存在>'}"
        )
    lines.append(
        f"[核对] 本地迁移文件: {', '.join(local_summary) if local_summary else '<无>'}"
    )
    return lines


def reconcile_unknown(cfg: MigrationConfig, state: dict[str, Any]) -> None:
    """上次状态未知(unknown 或中断遗留 running)时,核对远端事实 vs 状态文件并打印。

    只读核对,不修改数据库与状态文件;不能仅因锁已释放就重跑(设计 10 §1)。
    """
    print(
        f"[核对] 目标 {cfg.target}({cfg.db_name}) 上次状态: {state.get('status')}(待核对)"
    )
    if state.get("corrupt"):
        print(f"[核对] 状态文件异常: {state['corrupt']}")
    with open_short_connection(cfg) as conn:
        remote = read_remote_facts(cfg, conn)
    if cfg.tool == "flyway":
        local_summary = [
            f"{item['file']}(sha256={item['sha256'][:12]})"
            for item in scan_flyway_files(cfg)
        ]
        recorded = (state.get("facts") or {}).get("applied_versions", [])
        lines = format_facts_for_print(cfg, remote, local_summary)
        lines.append(f"[核对] 状态文件记录 applied_versions: {recorded or '<无>'}")
        remote_applied = [h["version"] for h in remote["history"] if h["success"]]
        consistent = sorted(remote_applied) == sorted(str(v) for v in recorded)
        lines.append(
            f"[核对] 结论: 远端已应用 {remote_applied};与状态文件记录{'一致' if consistent else '不一致,需人工确认差异原因'}"
        )
        failed_rows = [h for h in remote["history"] if not h["success"]]
        if failed_rows:
            lines.append(
                f"[核对] 注意: 远端存在 success=false 的历史行 {[h['version'] for h in failed_rows]}(上次迁移失败痕迹,禁止 repair)"
            )
    else:
        revisions = scan_alembic_versions(cfg)
        head = alembic_head(revisions)
        local_summary = [
            f"{info['file']}(rev={rev}, sha256={info['sha256'][:12]})"
            for rev, info in revisions.items()
        ]
        lines = format_facts_for_print(cfg, remote, local_summary)
        recorded = (state.get("facts") or {}) or {}
        lines.append(f"[核对] 状态文件记录 version: {recorded.get('version', '<无>')}")
        recorded_shas = recorded.get("revision_shas") or {}
        tampered = [
            rev
            for rev, sha in recorded_shas.items()
            if rev not in revisions or revisions[rev]["sha256"] != sha
        ]
        if tampered:
            lines.append(
                f"[核对] 注意: revision 文件相对上次记录发生变化: {tampered}(已执行脚本不可改写,设计 10 §2)"
            )
        lines.append(
            f"[核对] 结论: 远端 {remote['version'] or '<空>'},本地 head {head};"
            f"与状态文件记录{'一致' if recorded.get('version') == remote['version'] else '不一致,需人工确认差异原因'}"
        )
    for line in lines:
        print(line)


# ---------------------------------------------------------------------------
# 迁移链步骤
# ---------------------------------------------------------------------------


def step_validate_flyway(cfg: MigrationConfig) -> tuple[bool, str]:
    """历史校验:flyway validate(校验已执行 SQL 的历史与摘要;失败不 repair)。"""
    return run_flyway(cfg, "validate")


def step_validate_alembic(
    cfg: MigrationConfig, state: dict[str, Any] | None
) -> tuple[bool, str]:
    """alembic 前置校验:本地单一 head + 已记录 revision 文件 SHA-256 比对。"""
    revisions = scan_alembic_versions(cfg)
    head = alembic_head(revisions)
    recorded_shas = ((state or {}).get("facts") or {}).get("revision_shas") or {}
    tampered = [
        rev
        for rev, sha in recorded_shas.items()
        if rev not in revisions or revisions[rev]["sha256"] != sha
    ]
    if tampered:
        return (
            False,
            f"已执行的 revision 文件被改写: {tampered}(设计 10 §2:另记录 SHA-256 且不可改写历史)",
        )
    # 容器内执行 alembic heads/current,验证环境可加载且远端版本在图内
    ok_heads, out_heads = run_alembic(cfg, ["heads"])
    if not ok_heads:
        return False, f"alembic heads 执行失败:\n{out_heads}"
    return True, f"单一 head={head} 校验通过\n{out_heads}"


def step_migrate(cfg: MigrationConfig) -> tuple[bool, str]:
    """执行扩展迁移:flyway migrate / alembic upgrade head(无重试)。"""
    if cfg.tool == "flyway":
        return run_flyway(cfg, "migrate")
    return run_alembic(cfg, ["upgrade", "head"])


def step_verify(cfg: MigrationConfig, conn: psycopg.Connection) -> tuple[bool, str]:
    """schema 检查:实际应用结果与本地迁移目标一致(固定迁移目标校验)。"""
    remote = read_remote_facts(cfg, conn)
    if cfg.tool == "flyway":
        applied = sorted(h["version"] for h in remote["history"] if h["success"])
        failed = [h["version"] for h in remote["history"] if not h["success"]]
        local = sorted(item["version"] for item in scan_flyway_files(cfg))
        if failed:
            return False, f"flyway 历史存在 success=false 的行: {failed}"
        if applied != local:
            return False, f"应用版本 {applied} 与本地迁移文件 {local} 不一致"
        return True, f"flyway 已应用版本与应用目标一致: {applied}"
    head = alembic_head(scan_alembic_versions(cfg))
    if remote["version"] != head:
        return False, f"alembic_version={remote['version']} 与本地 head={head} 不一致"
    return True, f"alembic 已达到目标 revision: {head}"


def snapshot_facts(cfg: MigrationConfig, conn: psycopg.Connection) -> dict[str, Any]:
    """成功后记录远端事实快照,供下次 unknown 核对比对。"""
    remote = read_remote_facts(cfg, conn)
    if cfg.tool == "flyway":
        return {
            "applied_versions": sorted(
                h["version"] for h in remote["history"] if h["success"]
            ),
        }
    revisions = scan_alembic_versions(cfg)
    return {
        "version": remote["version"],
        "revision_shas": {rev: info["sha256"] for rev, info in revisions.items()},
    }


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="migrate.py",
        description="数据库迁移协调入口(T-11A):统一 advisory lock、状态文件、历史校验与容器化 Flyway/Alembic 执行。",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--env-file",
        help="环境文件(KEY=VALUE:PGHOST/PGPORT/JDBC_HOST/JDBC_PORT/DOCKER_NETWORK/MIGRATOR_PASSWORD_FILE)",
    )
    parser.add_argument(
        "--target", required=True, help="资源逻辑名(如 identity-db),同时用作状态文件名"
    )
    parser.add_argument(
        "--resources",
        default=str(DEFAULT_RESOURCES_PATH),
        help="资源声明清单(infra/resources.yaml)",
    )
    parser.add_argument(
        "--tool", required=True, choices=["flyway", "alembic"], help="迁移工具"
    )
    parser.add_argument(
        "--migrations-dir",
        required=True,
        help="迁移目录(flyway SQL 或 alembic env 目录)",
    )
    parser.add_argument("--db-name", help="显式覆盖目标库名")
    parser.add_argument("--db-user", help="显式覆盖 migrator 账号")
    parser.add_argument(
        "--owner-role", help="显式覆盖 NOLOGIN owner 角色(DDL 以该角色执行)"
    )
    parser.add_argument("--password-file", help="migrator 密码文件路径")
    parser.add_argument("--db-host", help="控制连接主机(宿主视角,默认 127.0.0.1)")
    parser.add_argument("--db-port", type=int, help="控制连接端口(默认 5432)")
    parser.add_argument("--jdbc-host", help="容器内访问 PG 的主机(默认同 --db-host)")
    parser.add_argument(
        "--jdbc-port", type=int, help="容器内访问 PG 的端口(默认同 --db-port)"
    )
    parser.add_argument("--docker-network", help="容器步骤接入的 docker 网络")
    parser.add_argument("--state-dir", help="状态文件目录；默认跟随环境文件隔离")
    parser.add_argument(
        "--check", action="store_true", help="只校验不执行(不取锁、不写状态)"
    )
    parser.add_argument("--yes", action="store_true", help="确认执行待应用迁移")
    parser.add_argument(
        "--confirm-unknown", action="store_true", help="人工核对 unknown 状态后确认继续"
    )
    parser.add_argument(
        "--lock-wait", type=float, default=10.0, help="迁移锁等待秒数,超时退出码 3"
    )
    parser.add_argument(
        "--step-timeout", type=int, default=900, help="单个容器步骤超时秒数"
    )
    return parser


def run_check_mode(cfg: MigrationConfig) -> int:
    """--check:只校验不执行(不取锁、不写状态、不做任何变更)。"""
    print(
        f"[check] 目标 {cfg.target} 工具 {cfg.tool} 目录 {to_posix(cfg.migrations_dir)}"
    )
    with open_short_connection(cfg) as conn:
        remote = read_remote_facts(cfg, conn)
    if cfg.tool == "flyway":
        local_summary = [item["file"] for item in scan_flyway_files(cfg)]
        for line in format_facts_for_print(cfg, remote, local_summary):
            print(line)
        pending = compute_pending(cfg, remote)
        print(f"[check] 待执行版本: {pending or '<无>'}")
        ok, output = run_flyway(cfg, "validate")
        print(output)
        if not ok:
            print("[check] 结论: 历史校验失败")
            return EXIT_STEP_FAILED
        print("[check] 结论: 校验通过")
        return EXIT_OK
    state = load_state(cfg)
    ok, output = step_validate_alembic(cfg, state)
    print(output)
    pending = compute_pending(cfg, remote)
    print(f"[check] 待执行: {pending or '<无>'}")
    if not ok:
        print("[check] 结论: 校验失败")
        return EXIT_STEP_FAILED
    print("[check] 结论: 校验通过")
    return EXIT_OK


def run_migration(cfg: MigrationConfig, args: argparse.Namespace) -> int:
    """主迁移链:unknown 核对 -> 取锁 -> validate -> (migrate -> verify)。"""
    state = load_state(cfg)

    # 重启恢复:unknown 或中断遗留的 running 都必须先核对远端事实
    if state and state.get("status") in (STATUS_UNKNOWN, STATUS_RUNNING):
        reconcile_unknown(cfg, state)
        if not args.confirm_unknown:
            print(
                "[中止] 上次状态待核对:请人工确认上述核对结果后加 --confirm-unknown 才能继续"
                "(设计 10:不能仅因锁已释放就重跑)。退出码 5。"
            )
            return EXIT_NEED_CONFIRM
        print(
            "[核对] 已收到 --confirm-unknown:继续执行,实际执行内容由迁移工具历史决定。"
        )

    try:
        control = acquire_lock(cfg, args.lock_wait)
    except TimeoutError:
        print(
            f"[中止] 迁移锁 pg_advisory_lock(hashtext('{cfg.db_name}')) 被其他进程持有"
            f"(等待 {args.lock_wait}s 超时):已有迁移进程在执行,本进程不抢锁。退出码 3。"
        )
        return EXIT_LOCK_BUSY

    previous_state = state
    run_state = new_run_state(cfg, previous_state)
    try:
        with control:
            cfg.control_connection = control
            previous_state = load_state(cfg)
            run_state = new_run_state(cfg, previous_state)
            save_state(cfg, run_state)
            print(
                f"[锁] 已持有 pg_advisory_lock(hashtext('{cfg.db_name}')),开始迁移链。"
            )

            # 步骤 1:历史校验(无论是否有待执行迁移都必须执行,拦截历史篡改)
            control_health_check(control)
            started_at = now_iso()
            if cfg.tool == "flyway":
                ok, output = step_validate_flyway(cfg)
            else:
                ok, output = step_validate_alembic(cfg, previous_state)
            record_step(
                run_state, "validate", started_at, "ok" if ok else "failed", output
            )
            save_state(cfg, run_state)
            print(output)
            if not ok:
                run_state["status"] = STATUS_FAILED
                save_state(cfg, run_state)
                print("[中止] 历史校验失败:停止本次发布,不 repair。退出码 1。")
                return EXIT_STEP_FAILED

            with open_short_connection(cfg) as conn:
                remote = read_remote_facts(cfg, conn)
            pending = compute_pending(cfg, remote)
            if not pending:
                run_state["status"] = STATUS_DONE
                run_state["facts"] = snapshot_facts(cfg, control)
                save_state(cfg, run_state)
                print(f"[完成] 目标 {cfg.target} 已是最新,无需迁移。")
                return EXIT_OK
            if not args.yes:
                # 未获确认:恢复原状态文件内容(本次未产生任何数据库变更)
                if previous_state is not None:
                    previous_state["updated_at"] = now_iso()
                    save_state(cfg, previous_state)
                else:
                    remove_state(cfg)
                print(f"[待确认] 待执行迁移: {pending};加 --yes 才执行。退出码 6。")
                return EXIT_NEED_YES

            # 步骤 2:执行迁移(flyway migrate / alembic upgrade head)
            control_health_check(control)
            started_at = now_iso()
            ok, output = step_migrate(cfg)
            record_step(
                run_state, "migrate", started_at, "ok" if ok else "failed", output
            )
            save_state(cfg, run_state)
            print(output)
            if not ok:
                run_state["status"] = STATUS_FAILED
                save_state(cfg, run_state)
                print(
                    "[中止] 迁移步骤失败:停止,不重试、不 clean、不 repair。退出码 1。"
                )
                return EXIT_STEP_FAILED

            # 步骤 3:schema 检查(经控制连接核实实际应用结果)
            control_health_check(control)
            started_at = now_iso()
            ok, output = step_verify(cfg, control)
            record_step(
                run_state, "verify", started_at, "ok" if ok else "failed", output
            )
            save_state(cfg, run_state)
            print(output)
            if not ok:
                run_state["status"] = STATUS_FAILED
                save_state(cfg, run_state)
                print("[中止] schema 检查失败:状态 failed,待人工核对。退出码 1。")
                return EXIT_STEP_FAILED

            run_state["status"] = STATUS_DONE
            run_state["facts"] = snapshot_facts(cfg, control)
            save_state(cfg, run_state)
            print(f"[完成] 目标 {cfg.target} 迁移链执行成功。")
            return EXIT_OK
    except (ControlLostError, StepFailedError, KeyboardInterrupt) as exc:
        run_state["status"] = STATUS_UNKNOWN
        save_state(cfg, run_state)
        print(f"[中止] {exc};已停止调度新步骤,状态标 unknown(待核对)。退出码 4。")
        return EXIT_CONTROL_LOST
    finally:
        cfg.control_connection = None


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        env = parse_env_file(Path(args.env_file)) if args.env_file else {}
        args.resources = Path(args.resources).resolve()
        cfg = resolve_config(args, env)
        if args.check:
            return run_check_mode(cfg)
        return run_migration(cfg, args)
    except UsageError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return EXIT_USAGE


if __name__ == "__main__":
    sys.exit(main())
