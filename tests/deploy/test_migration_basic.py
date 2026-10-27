"""T-11A 普通服务数据库迁移入口与恢复基础测试(实施计划 T-11A / AC-16 子场景)。

全部用例使用真实 PostgreSQL 17.11 容器执行(端口隔离 15532/15533,容器名
t11a-*);检测不到可用 Docker 时自动 skip 并写明原因。

覆盖场景:
a. 双进程争锁:锁被持有时,另一进程取锁失败退出,不抢锁;
b. 历史篡改:已应用 V1 内容被修改后,flyway validate 失败停止;
c. DDL 失败停止:坏迁移 -> 状态 failed,后续步骤(verify)未执行;
d. 运行账号无 DDL:runtime 角色 CREATE TABLE 被拒,DML 正常;
e. 空库与旧库:新库全量应用;已应用 V1 的库增量到 V2(flyway 与 alembic 各自入口);
f. 回填中断续跑:kill 回填进程后重跑,upsert 幂等,总数与游标正确;
g. 隔离恢复:pgBackRest 备份 -> 恢复到第二个全新容器,表行数/checksum 一致;
h. unknown 状态重启核对:远端事实 vs 状态文件,--confirm-unknown 门禁。

测试 fixture 自建 t11a 专用角色(NOLOGIN owner + migrator + runtime,设计 03 §6),
不依赖 infra/init(那是 T-02 并行任务的交付物)。
"""

from __future__ import annotations

import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

import psycopg
import pytest

ROOT = Path(__file__).resolve().parents[2]  # 仓库根
SCRIPTS_DIR = ROOT / "tools" / "deploy"
FIXTURES = ROOT / "tests" / "deploy" / "fixtures"
FLYWAY_DIR = FIXTURES / "flyway"
ALEMBIC_DIR = FIXTURES / "alembic"
BACKFILL_SCRIPT = FIXTURES / "backfill" / "batch_upsert.py"


def unused_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


RUN_ID = uuid.uuid4().hex[:8]
EVIDENCE_DIR = ROOT / ".local" / "test-evidence" / f"t11a-{RUN_ID}"
PG_PORT = unused_port()
PG2_PORT = unused_port()
NETWORK = f"t11a-{RUN_ID}-net"
CONTAINER = f"t11a-{RUN_ID}-pg"
CONTAINER2 = f"t11a-{RUN_ID}-pg2"
STANZA = "t11a"
VOLUMES = (
    f"t11a-{RUN_ID}-pg-data",
    f"t11a-{RUN_ID}-pg2-data",
    f"t11a-{RUN_ID}-backup-repo",
)
PG_IMAGE = "postgres:17.11"


def _docker_available() -> tuple[bool, str]:
    """导入期探测 Docker;不可用时整个模块 skip 并写明原因。"""
    if shutil.which("docker") is None:
        return False, "未找到 docker 命令"
    try:
        proc = subprocess.run(
            ["docker", "info", "--format", "{{.ServerVersion}}"],
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, f"docker daemon 探测失败: {exc}"
    if proc.returncode != 0:
        return False, f"docker daemon 不可用: {proc.stderr.strip()[:200]}"
    return True, ""


DOCKER_OK, DOCKER_REASON = _docker_available()
pytestmark = pytest.mark.skipif(
    not DOCKER_OK,
    reason=f"本测试需要真实 Docker 与 PG 容器;跳过原因: {DOCKER_REASON or '未知'}",
)


# ---------------------------------------------------------------------------
# 基础辅助
# ---------------------------------------------------------------------------


def docker(args: list[str], timeout: int = 180) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["docker", *args],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def docker_exec(
    container: str, args: list[str], user: str | None = None, timeout: int = 300
) -> subprocess.CompletedProcess[str]:
    full = ["docker", "exec"]
    if user:
        full += ["-u", user]
    full += [container, *args]
    return subprocess.run(
        full,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def wait_pg_ready(
    host: str, port: int, dbname: str, user: str, password: str, timeout: float = 90
) -> None:
    """轮询等待 PG 可连接(容器启动/恢复期间使用)。"""
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with psycopg.connect(
                host=host,
                port=port,
                dbname=dbname,
                user=user,
                password=password,
                connect_timeout=3,
            ):
                return
        except psycopg.Error as exc:
            last_error = exc
            time.sleep(1.0)
    raise AssertionError(f"PG 未在 {timeout}s 内就绪: {last_error}")


def run_py_script(
    args: list[str], timeout: int = 600
) -> subprocess.CompletedProcess[str]:
    """以当前解释器运行仓库内 Python 脚本;统一 UTF-8 编码避免控制台代码页干扰断言。"""
    env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
    return subprocess.run(
        [sys.executable, *args],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        env=env,
    )


def write_evidence(name: str, text: str) -> None:
    """把关键输出留档到 .local/test-evidence/(该目录被 .gitignore 忽略)。"""
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    (EVIDENCE_DIR / f"{name}.txt").write_text(text, encoding="utf-8")


def copy_flyway_dir(dest: Path, versions: tuple[str, ...] = ("1", "2", "3")) -> Path:
    """复制样例 flyway 目录的指定子集到临时目录(篡改/增量场景互不影响 fixture 原件)。"""
    dest.mkdir(parents=True, exist_ok=True)
    wanted = {
        f"V{v}__{name}.sql"
        for v, name in [
            ("1", "create_core_tables"),
            ("2", "add_display_name"),
            ("3", "broken_reference"),
        ]
        if v in versions
    }
    for name in wanted:
        shutil.copy2(FLYWAY_DIR / name, dest / name)
    return dest


def copy_alembic_dir(
    dest: Path, revisions: tuple[str, ...] = ("t11a0001", "t11a0002")
) -> Path:
    """复制样例 alembic 环境的指定 revision 子集。"""
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ALEMBIC_DIR / "alembic.ini", dest / "alembic.ini")
    shutil.copy2(ALEMBIC_DIR / "env.py", dest / "env.py")
    (dest / "versions").mkdir()
    for rev in revisions:
        matches = list((ALEMBIC_DIR / "versions").glob(f"{rev}_*.py"))
        assert matches, f"fixture 缺少 revision {rev}"
        shutil.copy2(matches[0], dest / "versions" / matches[0].name)
    return dest


# ---------------------------------------------------------------------------
# 会话级 fixture:PG 容器与账号模型
# ---------------------------------------------------------------------------


class PgEnv:
    """源实例 t11a-pg 的连接信息与账号模型(测试密码均为随机生成,不落仓库)。"""

    def __init__(self, super_password: str, secrets_dir: Path) -> None:
        self.super_password = super_password
        self.secrets_dir = secrets_dir
        self.fw = self._provision_account_set("t11a_fw")
        self.alc = self._provision_account_set("t11a_alc")

    def _provision_account_set(self, prefix: str) -> dict[str, str]:
        """按 NOLOGIN owner + migrator + runtime 模型创建一组账号(设计 03 §6)。"""
        from psycopg import sql as pg_sql

        owner = f"{prefix}_owner"
        migrator = f"{prefix}_migrator"
        runtime = f"{prefix}_runtime"
        migrator_password = secrets.token_urlsafe(18)
        runtime_password = secrets.token_urlsafe(18)
        with self.super_connect() as conn:
            conn.execute(
                pg_sql.SQL("CREATE ROLE {} NOLOGIN").format(pg_sql.Identifier(owner))
            )
            conn.execute(
                pg_sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(
                    pg_sql.Identifier(migrator), pg_sql.Literal(migrator_password)
                )
            )
            conn.execute(
                pg_sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(
                    pg_sql.Identifier(runtime), pg_sql.Literal(runtime_password)
                )
            )
            # migrator 是 owner 的成员:经连接参数 SET ROLE 以 owner 身份执行 DDL
            conn.execute(
                pg_sql.SQL("GRANT {} TO {}").format(
                    pg_sql.Identifier(owner), pg_sql.Identifier(migrator)
                )
            )
        migrator_file = self.secrets_dir / f"{migrator}.pw"
        runtime_file = self.secrets_dir / f"{runtime}.pw"
        migrator_file.write_text(migrator_password, encoding="utf-8")
        runtime_file.write_text(runtime_password, encoding="utf-8")
        return {
            "owner": owner,
            "migrator": migrator,
            "runtime": runtime,
            "migrator_password": migrator_password,
            "migrator_password_file": str(migrator_file),
            "runtime_password": runtime_password,
        }

    def super_connect(self, autocommit: bool = True):
        return psycopg.connect(
            host="127.0.0.1",
            port=PG_PORT,
            dbname="postgres",
            user="postgres",
            password=self.super_password,
            autocommit=autocommit,
        )

    def create_database(self, db_name: str, owner: str) -> None:
        with self.super_connect() as conn:
            conn.execute(f'CREATE DATABASE "{db_name}" OWNER "{owner}"')

    def connect(self, dbname: str, user: str, password: str, autocommit: bool = True):
        return psycopg.connect(
            host="127.0.0.1",
            port=PG_PORT,
            dbname=dbname,
            user=user,
            password=password,
            autocommit=autocommit,
        )


@pytest.fixture(scope="session")
def pg_env(tmp_path_factory: pytest.TempPathFactory) -> PgEnv:
    """启动源 PG 容器 t11a-pg(15532,开启 WAL 归档供 pgBackRest 使用)。"""
    # 防御性清理上次中断残留
    docker(["rm", "-f", CONTAINER, CONTAINER2])
    docker(["network", "rm", NETWORK])
    for volume in VOLUMES:
        docker(["volume", "rm", "-f", volume])

    assert docker(["network", "create", NETWORK]).returncode == 0
    super_password = secrets.token_urlsafe(18)
    proc = docker(
        [
            "run",
            "-d",
            "--name",
            CONTAINER,
            "--network",
            NETWORK,
            "--network-alias",
            CONTAINER,
            "-p",
            f"127.0.0.1:{PG_PORT}:5432",
            "-e",
            f"POSTGRES_PASSWORD={super_password}",
            "-v",
            f"{VOLUMES[0]}:/var/lib/postgresql/data",
            "-v",
            f"{VOLUMES[2]}:/var/lib/pgbackrest",
            PG_IMAGE,
            "-c",
            "archive_mode=on",
            "-c",
            f"archive_command=pgbackrest --stanza={STANZA} archive-push %p",
        ],
        timeout=300,
    )
    assert proc.returncode == 0, f"启动 PG 容器失败: {proc.stderr}"
    wait_pg_ready("127.0.0.1", PG_PORT, "postgres", "postgres", super_password)

    yield PgEnv(super_password, tmp_path_factory.mktemp("secrets"))

    docker(["rm", "-f", CONTAINER, CONTAINER2])
    docker(["network", "rm", NETWORK])
    for volume in VOLUMES:
        docker(["volume", "rm", "-f", volume])


# ---------------------------------------------------------------------------
# migrate.py 调用辅助
# ---------------------------------------------------------------------------


def run_migrate(
    pg_env: PgEnv,
    account: dict[str, str],
    *,
    target: str,
    tool: str,
    migrations_dir: Path,
    db_name: str,
    state_dir: Path,
    extra: tuple[str, ...] = (),
) -> subprocess.CompletedProcess[str]:
    """以显式覆盖参数调用迁移入口(容器内经 t11a-net 访问 PG)。"""
    cmd = [
        str(SCRIPTS_DIR / "migrate.py"),
        "--target",
        target,
        "--tool",
        tool,
        "--migrations-dir",
        str(migrations_dir),
        "--db-name",
        db_name,
        "--db-user",
        account["migrator"],
        "--owner-role",
        account["owner"],
        "--password-file",
        account["migrator_password_file"],
        "--db-host",
        "127.0.0.1",
        "--db-port",
        str(PG_PORT),
        "--jdbc-host",
        CONTAINER,
        "--jdbc-port",
        "5432",
        "--docker-network",
        NETWORK,
        "--state-dir",
        str(state_dir),
        *extra,
    ]
    return run_py_script(cmd)


def load_state(state_dir: Path, target: str) -> dict:
    return json.loads((state_dir / f"{target}.json").read_text(encoding="utf-8"))


def flyway_history_rows(
    pg_env: PgEnv, db_name: str, account: dict[str, str]
) -> list[tuple]:
    with pg_env.connect(
        db_name, account["migrator"], account["migrator_password"]
    ) as conn:
        return conn.execute(
            "SELECT version, success FROM public.flyway_schema_history ORDER BY installed_rank"
        ).fetchall()


def column_exists(conn: psycopg.Connection, table: str, column: str) -> bool:
    return conn.execute(
        "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
        "WHERE table_schema = 'public' AND table_name = %s AND column_name = %s)",
        (table, column),
    ).fetchone()[0]


# ---------------------------------------------------------------------------
# a. 双进程争锁
# ---------------------------------------------------------------------------


def test_a_lock_contention(pg_env: PgEnv, tmp_path: Path) -> None:
    """一个进程持锁期间,另一进程 acquire 失败退出;锁释放后才能继续。"""
    account = pg_env.fw
    db_name = "t11a_fw_lock"
    pg_env.create_database(db_name, account["owner"])
    migrations = copy_flyway_dir(tmp_path / "mig")
    state_dir = tmp_path / "state"

    # 第一持有者:与 migrate.py 相同的锁键 pg_advisory_lock(hashtext(目标库名))
    with pg_env.connect(
        db_name, account["migrator"], account["migrator_password"]
    ) as holder:
        holder.execute("SELECT pg_advisory_lock(hashtext(%s))", (db_name,))

        result = run_migrate(
            pg_env,
            account,
            target="t11a_lock",
            tool="flyway",
            migrations_dir=migrations,
            db_name=db_name,
            state_dir=state_dir,
            extra=("--lock-wait", "3", "--yes"),
        )
        assert result.returncode == 3, (
            f"持锁期间应退出码 3,实际 {result.returncode}\n{result.stdout}\n{result.stderr}"
        )
        assert "锁" in result.stdout, result.stdout
        # 未获锁:不应产生任何步骤执行与状态文件
        assert not (state_dir / "t11a_lock.json").exists()
    # 持有者连接关闭 -> 锁释放
    result2 = run_migrate(
        pg_env,
        account,
        target="t11a_lock",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
        extra=("--lock-wait", "10"),
    )
    # 锁可取:进入待确认(存在待执行迁移但未给 --yes,退出码 6)
    assert result2.returncode == 6, (
        f"锁释放后应能取锁(退出码 6 待确认),实际 {result2.returncode}\n{result2.stdout}\n{result2.stderr}"
    )

    write_evidence(
        "a_lock_contention",
        f"$ migrate.py(持锁期间,--lock-wait 3)\n退出码={result.returncode}\n{result.stdout}\n"
        f"$ migrate.py(锁释放后,未给 --yes)\n退出码={result2.returncode}\n{result2.stdout}\n",
    )


# ---------------------------------------------------------------------------
# b. 历史篡改
# ---------------------------------------------------------------------------


def test_b_history_tamper_detected(pg_env: PgEnv, tmp_path: Path) -> None:
    """应用 V1/V2 后修改 V1 内容:flyway validate 校验失败,停止且不 repair。"""
    account = pg_env.fw
    db_name = "t11a_fw_tamper"
    pg_env.create_database(db_name, account["owner"])
    migrations = copy_flyway_dir(tmp_path / "mig", versions=("1", "2"))
    state_dir = tmp_path / "state"

    first = run_migrate(
        pg_env,
        account,
        target="t11a_tamper",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
        extra=("--yes",),
    )
    assert first.returncode == 0, f"{first.stdout}\n{first.stderr}"

    # 篡改已应用的 V1(追加内容改变 checksum)
    v1_file = migrations / "V1__create_core_tables.sql"
    v1_file.write_text(
        v1_file.read_text(encoding="utf-8")
        + "\n-- 篡改:历史脚本不可改写(设计 10 §2)\n",
        encoding="utf-8",
    )

    second = run_migrate(
        pg_env,
        account,
        target="t11a_tamper",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
        extra=("--yes",),
    )
    assert second.returncode == 1, (
        f"历史篡改应失败退出 1,实际 {second.returncode}\n{second.stdout}\n{second.stderr}"
    )
    combined = (second.stdout + second.stderr).lower()
    assert "checksum" in combined, combined
    state = load_state(state_dir, "t11a_tamper")
    assert state["status"] == "failed"
    validate_step = [step for step in state["steps"] if step["name"] == "validate"]
    assert validate_step and validate_step[0]["status"] == "failed"
    # 迁移链在校验处停止,没有执行 migrate 步骤
    assert all(step["name"] != "migrate" for step in state["steps"])


# ---------------------------------------------------------------------------
# c. DDL 失败停止,后续步骤不执行
# ---------------------------------------------------------------------------


def test_c_ddl_failure_stops_chain(pg_env: PgEnv, tmp_path: Path) -> None:
    """坏迁移 V3 失败:状态 failed,V1/V2 已提交保留,verify 步骤未执行。"""
    account = pg_env.fw
    db_name = "t11a_fw_fail"
    pg_env.create_database(db_name, account["owner"])
    migrations = copy_flyway_dir(tmp_path / "mig", versions=("1", "2", "3"))
    state_dir = tmp_path / "state"

    result = run_migrate(
        pg_env,
        account,
        target="t11a_fail",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
        extra=("--yes",),
    )
    assert result.returncode == 1, f"{result.stdout}\n{result.stderr}"
    state = load_state(state_dir, "t11a_fail")
    assert state["status"] == "failed"
    executed = [step["name"] for step in state["steps"]]
    assert executed == ["validate", "migrate"], executed  # verify 未执行
    migrate_step = state["steps"][1]
    assert migrate_step["status"] == "failed"

    # 远端事实:V1/V2 成功提交;V3 未应用(PG 事务性 DDL 下失败迁移随事务回滚,
    # flyway 不会留下 success=false 的历史行;若出现则说明被人工修复过)
    rows = flyway_history_rows(pg_env, db_name, account)
    assert [row[0] for row in rows] == ["1", "2"]
    assert all(row[1] for row in rows)
    with pg_env.connect(
        db_name, account["migrator"], account["migrator_password"]
    ) as conn:
        has_oops = conn.execute(
            "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_tables "
            "WHERE schemaname = 'public' AND tablename = 't11a_oops')"
        ).fetchone()[0]
    assert not has_oops, "坏迁移中的表不应存在"


# ---------------------------------------------------------------------------
# d. 运行账号无 DDL
# ---------------------------------------------------------------------------


def test_d_runtime_account_no_ddl(pg_env: PgEnv, tmp_path: Path) -> None:
    """runtime 账号可 DML 但 CREATE TABLE 被拒(设计 03 §6:runtime 无 DDL)。"""
    account = pg_env.fw
    db_name = "t11a_fw_dd"
    pg_env.create_database(db_name, account["owner"])
    migrations = copy_flyway_dir(tmp_path / "mig", versions=("1", "2"))
    state_dir = tmp_path / "state"

    result = run_migrate(
        pg_env,
        account,
        target="t11a_dd",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
        extra=("--yes",),
    )
    assert result.returncode == 0, f"{result.stdout}\n{result.stderr}"

    with pg_env.connect(
        db_name, account["runtime"], account["runtime_password"]
    ) as conn:
        # 正向控制:runtime 的 DML 授权有效
        conn.execute(
            "INSERT INTO t11a_greeting (id, tenant_id, title) VALUES (1, 10, 'hello')"
        )
        count = conn.execute("SELECT count(*) FROM t11a_greeting").fetchone()[0]
        assert count == 1
        # 负向控制:runtime 不能建表(无 schema CREATE 权限)
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            conn.execute("CREATE TABLE t11a_forbidden (id INT PRIMARY KEY)")


# ---------------------------------------------------------------------------
# e. 空库与旧库(flyway / alembic 各自入口)
# ---------------------------------------------------------------------------


def test_e_flyway_empty_and_incremental(pg_env: PgEnv, tmp_path: Path) -> None:
    """新库全量应用 V1+V2;已应用 V1 的库增量到 V2 且数据保留。"""
    account = pg_env.fw

    # 空库:--check 先行校验通过,再全量应用
    fresh_db = "t11a_fw_e1"
    pg_env.create_database(fresh_db, account["owner"])
    fresh_dir = copy_flyway_dir(tmp_path / "fresh", versions=("1", "2"))
    state_dir = tmp_path / "fresh-state"
    check = run_migrate(
        pg_env,
        account,
        target="t11a_e1",
        tool="flyway",
        migrations_dir=fresh_dir,
        db_name=fresh_db,
        state_dir=state_dir,
        extra=("--check",),
    )
    assert check.returncode == 0, f"{check.stdout}\n{check.stderr}"
    applied = run_migrate(
        pg_env,
        account,
        target="t11a_e1",
        tool="flyway",
        migrations_dir=fresh_dir,
        db_name=fresh_db,
        state_dir=state_dir,
        extra=("--yes",),
    )
    assert applied.returncode == 0, f"{applied.stdout}\n{applied.stderr}"
    assert load_state(state_dir, "t11a_e1")["status"] == "done"
    with pg_env.connect(
        fresh_db, account["migrator"], account["migrator_password"]
    ) as conn:
        assert column_exists(conn, "t11a_greeting", "display_name")
    assert [
        row[0] for row in flyway_history_rows(pg_env, fresh_db, account) if row[1]
    ] == ["1", "2"]

    # 旧库:先只应用 V1,写入业务数据,再增量到 V2
    old_db = "t11a_fw_e2"
    pg_env.create_database(old_db, account["owner"])
    v1_dir = copy_flyway_dir(tmp_path / "v1only", versions=("1",))
    v2_dir = copy_flyway_dir(tmp_path / "v1v2", versions=("1", "2"))
    state_dir2 = tmp_path / "old-state"
    first = run_migrate(
        pg_env,
        account,
        target="t11a_e2",
        tool="flyway",
        migrations_dir=v1_dir,
        db_name=old_db,
        state_dir=state_dir2,
        extra=("--yes",),
    )
    assert first.returncode == 0, f"{first.stdout}\n{first.stderr}"
    with pg_env.connect(
        old_db, account["runtime"], account["runtime_password"]
    ) as conn:
        conn.execute(
            "INSERT INTO t11a_greeting (id, tenant_id, title) VALUES (7, 10, 'legacy')"
        )
    installed_rank_before = flyway_history_rows(pg_env, old_db, account)

    second = run_migrate(
        pg_env,
        account,
        target="t11a_e2",
        tool="flyway",
        migrations_dir=v2_dir,
        db_name=old_db,
        state_dir=state_dir2,
        extra=("--yes",),
    )
    assert second.returncode == 0, f"{second.stdout}\n{second.stderr}"
    rows = flyway_history_rows(pg_env, old_db, account)
    assert [row[0] for row in rows if row[1]] == ["1", "2"]
    # V1 未被重跑(历史行未变化),旧数据保留
    assert rows[0] == installed_rank_before[0]
    with pg_env.connect(
        old_db, account["runtime"], account["runtime_password"]
    ) as conn:
        row = conn.execute(
            "SELECT title, display_name FROM t11a_greeting WHERE id = 7"
        ).fetchone()
    assert row == ("legacy", None)


def test_e_alembic_empty_and_incremental(pg_env: PgEnv, tmp_path: Path) -> None:
    """alembic 入口:新库全量到 head;已应用 0001 的库增量到 0002。"""
    account = pg_env.alc

    fresh_db = "t11a_alc_e1"
    pg_env.create_database(fresh_db, account["owner"])
    fresh_dir = copy_alembic_dir(tmp_path / "alc-fresh")
    state_dir = tmp_path / "alc-state"
    applied = run_migrate(
        pg_env,
        account,
        target="t11a_alc1",
        tool="alembic",
        migrations_dir=fresh_dir,
        db_name=fresh_db,
        state_dir=state_dir,
        extra=("--yes",),
    )
    assert applied.returncode == 0, f"{applied.stdout}\n{applied.stderr}"
    state = load_state(state_dir, "t11a_alc1")
    assert state["status"] == "done"
    assert state["facts"]["version"] == "t11a0002"
    with pg_env.connect(
        fresh_db, account["migrator"], account["migrator_password"]
    ) as conn:
        assert column_exists(conn, "t11a_agent_note", "pinned")
    # alembic 库同样验证 runtime 无 DDL、DML 正常
    with pg_env.connect(
        fresh_db, account["runtime"], account["runtime_password"]
    ) as conn:
        conn.execute(
            "INSERT INTO t11a_agent_note (id, tenant_id, content) VALUES (1, 10, 'note')"
        )
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            conn.execute("CREATE TABLE t11a_alc_forbidden (id INT PRIMARY KEY)")

    old_db = "t11a_alc_e2"
    pg_env.create_database(old_db, account["owner"])
    r1_dir = copy_alembic_dir(tmp_path / "alc-r1", revisions=("t11a0001",))
    state_dir2 = tmp_path / "alc-state2"
    first = run_migrate(
        pg_env,
        account,
        target="t11a_alc2",
        tool="alembic",
        migrations_dir=r1_dir,
        db_name=old_db,
        state_dir=state_dir2,
        extra=("--yes",),
    )
    assert first.returncode == 0, f"{first.stdout}\n{first.stderr}"
    assert load_state(state_dir2, "t11a_alc2")["facts"]["version"] == "t11a0001"
    with pg_env.connect(
        old_db, account["runtime"], account["runtime_password"]
    ) as conn:
        conn.execute(
            "INSERT INTO t11a_agent_note (id, tenant_id, content) VALUES (2, 20, 'old')"
        )

    full_dir = copy_alembic_dir(tmp_path / "alc-full")
    second = run_migrate(
        pg_env,
        account,
        target="t11a_alc2",
        tool="alembic",
        migrations_dir=full_dir,
        db_name=old_db,
        state_dir=state_dir2,
        extra=("--yes",),
    )
    assert second.returncode == 0, f"{second.stdout}\n{second.stderr}"
    with pg_env.connect(
        old_db, account["migrator"], account["migrator_password"]
    ) as conn:
        version = conn.execute(
            "SELECT version_num FROM public.alembic_version"
        ).fetchone()[0]
        old_row = conn.execute(
            "SELECT content, pinned FROM t11a_agent_note WHERE id = 2"
        ).fetchone()
    assert version == "t11a0002"
    assert old_row == ("old", False)


# ---------------------------------------------------------------------------
# f. 回填中断续跑
# ---------------------------------------------------------------------------


def test_f_backfill_resume_after_kill(pg_env: PgEnv, tmp_path: Path) -> None:
    """kill 回填进程后重跑:upsert 幂等,总数、游标与值全部正确。"""
    account = pg_env.fw
    db_name = "t11a_fw_fill"
    pg_env.create_database(db_name, account["owner"])
    migrations = copy_flyway_dir(tmp_path / "mig", versions=("1", "2"))
    state_dir = tmp_path / "state"
    result = run_migrate(
        pg_env,
        account,
        target="t11a_fill",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
        extra=("--yes",),
    )
    assert result.returncode == 0, f"{result.stdout}\n{result.stderr}"

    total, batch = 3000, 20
    common_args = [
        str(BACKFILL_SCRIPT),
        "--host",
        "127.0.0.1",
        "--port",
        str(PG_PORT),
        "--db",
        db_name,
        "--user",
        account["migrator"],
        "--password-file",
        account["migrator_password_file"],
        "--total",
        str(total),
    ]
    # 启动回填(批间 0.25s 延迟,150 批约 40s,为轮询与强杀留足窗口);
    # stdout 同步落盘,便于进程卡住时观察已产生的输出
    backfill_log = EVIDENCE_DIR / "f_backfill_live.log"
    backfill_log.parent.mkdir(parents=True, exist_ok=True)
    with backfill_log.open("w", encoding="utf-8") as log_fh:
        killed = subprocess.Popen(
            [sys.executable, *common_args, "--batch", str(batch), "--delay", "0.25"],
            stdout=log_fh,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        )
    # 等待至少 3 个批次提交后强杀进程(模拟进程崩溃);轮询容忍回填脚本尚未建表
    deadline = time.monotonic() + 60
    progressed = False
    while time.monotonic() < deadline:
        if killed.poll() is not None:
            # 回填进程提前退出:带出真实输出,便于定位启动/连接问题
            output = killed.stdout.read() if killed.stdout else ""
            raise AssertionError(f"回填进程提前退出(rc={killed.returncode}):\n{output}")
        try:
            with pg_env.connect(
                db_name, account["migrator"], account["migrator_password"]
            ) as conn:
                cursor_row = conn.execute(
                    "SELECT last_id FROM t11a_backfill_progress WHERE id = 1"
                ).fetchone()
        except psycopg.errors.UndefinedTable:
            time.sleep(0.3)
            continue
        if cursor_row and cursor_row[0] >= 3 * batch:
            progressed = True
            break
        time.sleep(0.3)
    if not progressed:
        killed.kill()
        killed.wait(timeout=15)
        live = (
            backfill_log.read_text(encoding="utf-8", errors="replace")
            if backfill_log.exists()
            else ""
        )
        raise AssertionError(
            f"回填进度未推进,无法验证中断续跑;回填日志:\n{live[-2000:]}"
        )
    killed.kill()
    killed.wait(timeout=15)

    with pg_env.connect(
        db_name, account["migrator"], account["migrator_password"]
    ) as conn:
        partial_count = conn.execute("SELECT count(*) FROM t11a_backfill").fetchone()[0]
        partial_cursor = conn.execute(
            "SELECT last_id FROM t11a_backfill_progress"
        ).fetchone()[0]
    assert partial_count < total, "应在完成前被中断"
    assert partial_count == partial_cursor, "游标必须与已提交数据一致(同事务提交)"

    # 重跑到完成(不同批大小,验证与批次无关的幂等)
    resumed = run_py_script(
        [*common_args, "--batch", "50", "--delay", "0"], timeout=300
    )
    assert resumed.returncode == 0, f"{resumed.stdout}\n{resumed.stderr}"
    with pg_env.connect(
        db_name, account["migrator"], account["migrator_password"]
    ) as conn:
        final_count = conn.execute("SELECT count(*) FROM t11a_backfill").fetchone()[0]
        final_cursor = conn.execute(
            "SELECT last_id FROM t11a_backfill_progress"
        ).fetchone()[0]
        bad_values = conn.execute(
            "SELECT count(*) FROM t11a_backfill WHERE value <> 'v' || lpad(id::text, 6, '0')"
        ).fetchone()[0]
        distinct_ids = conn.execute(
            "SELECT count(DISTINCT id) FROM t11a_backfill"
        ).fetchone()[0]
    assert final_count == total
    assert final_cursor == total
    assert distinct_ids == total, "续跑不应产生重复或跳号"
    assert bad_values == 0, "upsert 幂等:重跑不得产生与确定性推导不一致的值"

    write_evidence(
        "f_backfill_resume",
        f"中断时: count={partial_count} cursor={partial_cursor}\n重跑后: count={final_count} cursor={final_cursor}\n{resumed.stdout}",
    )


# ---------------------------------------------------------------------------
# g. pgBackRest 隔离恢复
# ---------------------------------------------------------------------------


PGBACKREST_CONF = (
    "[global]\n"
    "repo1-path=/var/lib/pgbackrest\n"
    "log-level-console=info\n"
    "\n"
    f"[{STANZA}]\n"
    "pg1-path=/var/lib/postgresql/data\n"
)


def install_pgbackrest(container: str) -> subprocess.CompletedProcess[str]:
    """在 PG 容器内安装固定来源的 pgbackrest(PGDG apt)并写入配置。"""
    proc = docker_exec(
        container,
        ["bash", "-c", "apt-get update -qq >/dev/null 2>&1 || true"],
        timeout=300,
    )
    assert proc.returncode == 0, proc.stderr
    proc = docker_exec(
        container,
        [
            "bash",
            "-c",
            (
                "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends pgbackrest >/dev/null && "
                "mkdir -p /var/lib/pgbackrest /etc/pgbackrest && "
                "chown -R postgres:postgres /var/lib/pgbackrest && "
                f"printf '%s' '{PGBACKREST_CONF}' > /etc/pgbackrest/pgbackrest.conf"
            ),
        ],
        timeout=420,
    )
    assert proc.returncode == 0, f"pgbackrest 安装/配置失败: {proc.stderr}"
    return docker_exec(container, ["pgbackrest", "version"])


@pytest.fixture(scope="session")
def pgbackrest_ready(pg_env: PgEnv) -> str:
    """在源实例安装 pgbackrest、建立 stanza 并确认归档链路可用;返回版本信息。"""
    version = install_pgbackrest(CONTAINER)
    assert version.returncode == 0, version.stderr
    proc = docker_exec(
        CONTAINER,
        ["pgbackrest", "--stanza", STANZA, "stanza-create"],
        user="postgres",
        timeout=180,
    )
    assert proc.returncode == 0, f"stanza-create 失败: {proc.stdout} {proc.stderr}"
    # check 会强制 WAL 切换并确认已归档(归档链路验证)
    for _ in range(30):
        check = docker_exec(
            CONTAINER,
            ["pgbackrest", "--stanza", STANZA, "check"],
            user="postgres",
            timeout=120,
        )
        if check.returncode == 0:
            break
        time.sleep(2)
    else:
        raise AssertionError(f"pgbackrest check 未通过: {check.stdout} {check.stderr}")
    version_text = (version.stdout + version.stderr).strip()
    write_evidence("g_pgbackrest_version", version_text)
    return version_text


def table_fingerprint(conn: psycopg.Connection, table: str) -> tuple[int, str]:
    """行数 + 稳定 checksum(md5 of ordered row hashes),用于源/恢复实例比对。"""
    return conn.execute(
        f"SELECT count(*), coalesce(md5(string_agg(h, '' ORDER BY h)), '') "
        f"FROM (SELECT md5(t::text) AS h FROM {table} t) s"
    ).fetchone()


def test_g_isolated_restore(
    pg_env: PgEnv, pgbackrest_ready: str, tmp_path: Path
) -> None:
    """备份源库 -> pgBackRest 全量备份 + WAL -> 恢复到第二实例,行数/checksum 一致。"""
    account = pg_env.fw
    db_name = "t11a_recover"
    pg_env.create_database(db_name, account["owner"])
    with pg_env.connect(
        db_name, account["migrator"], account["migrator_password"]
    ) as conn:
        conn.execute("SET ROLE " + account["owner"])
        conn.execute(
            "CREATE TABLE t11a_recover_item (id BIGINT PRIMARY KEY, payload TEXT NOT NULL)"
        )
        conn.execute("CREATE TABLE t11a_recover_tag (tag TEXT PRIMARY KEY)")
        for i in range(1, 41):
            conn.execute(
                "INSERT INTO t11a_recover_item (id, payload) VALUES (%s, %s)",
                (i, f"before-backup-{i:03d}"),
            )
        conn.execute("RESET ROLE")

    # 全量备份(备份前 40 行)
    backup = docker_exec(
        CONTAINER,
        ["pgbackrest", "--stanza", STANZA, "--type=full", "backup"],
        user="postgres",
        timeout=420,
    )
    assert backup.returncode == 0, f"{backup.stdout}\n{backup.stderr}"

    # 备份后继续写入 25 行(依赖 WAL 归档恢复)
    with pg_env.connect(
        db_name, account["migrator"], account["migrator_password"]
    ) as conn:
        for i in range(41, 66):
            conn.execute(
                "INSERT INTO t11a_recover_item (id, payload) VALUES (%s, %s)",
                (i, f"after-backup-{i:03d}"),
            )
        conn.execute("INSERT INTO t11a_recover_tag (tag) VALUES ('t11a-marker')")
    # WAL 切换是运维动作:经超级用户连接执行(migrator 无 pg_switch_wal 权限,
    # 该权限边界本身即是设计 03 账号模型的正确表现)
    with pg_env.super_connect() as admin_conn:
        admin_conn.execute("SELECT pg_switch_wal()")
    time.sleep(4)  # 等待 archive-push 完成(同步 archive_command)

    with pg_env.connect(
        db_name, account["migrator"], account["migrator_password"]
    ) as source_conn:
        item_before = table_fingerprint(source_conn, "t11a_recover_item")
        tag_before = table_fingerprint(source_conn, "t11a_recover_tag")
    assert item_before[0] == 65 and tag_before[0] == 1

    # 第二个全新实例:不启动 postgres,先恢复再拉起
    proc = docker(
        [
            "run",
            "-d",
            "--name",
            CONTAINER2,
            "-p",
            f"127.0.0.1:{PG2_PORT}:5432",
            "-v",
            f"{VOLUMES[1]}:/var/lib/postgresql/data",
            "-v",
            f"{VOLUMES[2]}:/var/lib/pgbackrest",
            PG_IMAGE,
            "sleep",
            "infinity",
        ],
        timeout=300,
    )
    assert proc.returncode == 0, proc.stderr
    install_pgbackrest(CONTAINER2)
    prep = docker_exec(
        CONTAINER2,
        [
            "bash",
            "-c",
            (
                "mkdir -p /var/lib/postgresql/data /etc/pgbackrest /var/lib/pgbackrest && "
                "chown -R postgres:postgres /var/lib/postgresql/data /var/lib/pgbackrest && "
                "chmod 700 /var/lib/postgresql/data && "
                f"printf '%s' '{PGBACKREST_CONF}' > /etc/pgbackrest/pgbackrest.conf"
            ),
        ],
        timeout=120,
    )
    assert prep.returncode == 0, prep.stderr
    restore = docker_exec(
        CONTAINER2,
        [
            "pgbackrest",
            "--stanza",
            STANZA,
            "--pg1-path=/var/lib/postgresql/data",
            "restore",
        ],
        user="postgres",
        timeout=420,
    )
    assert restore.returncode == 0, f"restore 失败:\n{restore.stdout}\n{restore.stderr}"
    start = docker_exec(
        CONTAINER2,
        [
            "pg_ctl",
            "-D",
            "/var/lib/postgresql/data",
            "-l",
            "/tmp/pg-recovery.log",
            "-o",
            "-c listen_addresses=*",
            "-w",
            "start",
        ],
        user="postgres",
        timeout=300,
    )
    assert start.returncode == 0, f"恢复实例启动失败:\n{start.stdout}\n{start.stderr}"
    # 恢复后的集群保留源实例凭证
    wait_pg_ready(
        "127.0.0.1", PG2_PORT, db_name, "postgres", pg_env.super_password, timeout=120
    )

    with psycopg.connect(
        host="127.0.0.1",
        port=PG2_PORT,
        dbname=db_name,
        user="postgres",
        password=pg_env.super_password,
    ) as restored_conn:
        item_after = table_fingerprint(restored_conn, "t11a_recover_item")
        tag_after = table_fingerprint(restored_conn, "t11a_recover_tag")

    assert item_after == item_before, (
        f"t11a_recover_item 不一致: {item_before} vs {item_after}"
    )
    assert tag_after == tag_before, (
        f"t11a_recover_tag 不一致: {tag_before} vs {tag_after}"
    )

    write_evidence(
        "g_isolated_restore",
        f"pgbackrest: {pgbackrest_ready}\n"
        f"backup:\n{backup.stdout}\nrestore:\n{restore.stdout}\n"
        f"item 行数/checksum: 源={item_before} 恢复={item_after}\n"
        f"tag  行数/checksum: 源={tag_before} 恢复={tag_after}\n",
    )


# ---------------------------------------------------------------------------
# h. unknown 状态重启核对
# ---------------------------------------------------------------------------


def test_h_unknown_state_requires_confirmation(pg_env: PgEnv, tmp_path: Path) -> None:
    """上次状态 running/unknown:先核对远端事实,--confirm-unknown 才继续。"""
    account = pg_env.fw
    db_name = "t11a_fw_unk"
    pg_env.create_database(db_name, account["owner"])
    migrations = copy_flyway_dir(tmp_path / "mig", versions=("1", "2"))
    state_dir = tmp_path / "state"

    first = run_migrate(
        pg_env,
        account,
        target="t11a_unk",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
        extra=("--yes",),
    )
    assert first.returncode == 0, f"{first.stdout}\n{first.stderr}"

    # 模拟控制连接丢失/进程崩溃:状态被标为 unknown(与真实控制连接断开时的落盘状态一致)
    state_file = state_dir / "t11a_unk.json"
    state = json.loads(state_file.read_text(encoding="utf-8"))
    state["status"] = "unknown"
    state["facts"] = {
        "applied_versions": ["1"]
    }  # 刻意与远端(1,2)不一致,验证核对内容真实
    state["steps"] = [
        {
            "name": "validate",
            "status": "ok",
            "started_at": "",
            "finished_at": "",
            "detail": "",
        }
    ]
    state_file.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")

    blocked = run_migrate(
        pg_env,
        account,
        target="t11a_unk",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
    )
    assert blocked.returncode == 5, (
        f"未确认时应退出码 5,实际 {blocked.returncode}\n{blocked.stdout}\n{blocked.stderr}"
    )
    assert "[核对]" in blocked.stdout
    assert "flyway_schema_history" in blocked.stdout
    assert "--confirm-unknown" in blocked.stdout
    # 中止路径不产生新步骤,状态保持 unknown
    assert load_state(state_dir, "t11a_unk")["status"] == "unknown"

    confirmed = run_migrate(
        pg_env,
        account,
        target="t11a_unk",
        tool="flyway",
        migrations_dir=migrations,
        db_name=db_name,
        state_dir=state_dir,
        extra=("--confirm-unknown",),
    )
    assert confirmed.returncode == 0, f"{confirmed.stdout}\n{confirmed.stderr}"
    final_state = load_state(state_dir, "t11a_unk")
    assert final_state["status"] == "done"

    write_evidence(
        "h_unknown_state",
        f"$ migrate.py(状态 unknown,未确认)\n退出码={blocked.returncode}\n{blocked.stdout}\n"
        f"$ migrate.py --confirm-unknown\n退出码={confirmed.returncode}\n{confirmed.stdout}\n",
    )
